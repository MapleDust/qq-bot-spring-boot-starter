package top.fcidd.qqbot.starter.autoconfigure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.client.HttpClient;
import top.fcidd.qqbot.starter.event.BotEvent;
import top.fcidd.qqbot.starter.event.EventHandler;
import top.fcidd.qqbot.starter.util.JsonUtils;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Log4j2
public class QQBotWebSocketClient {

    /** 心跳间隔的倍数：超过该倍数没收到任何消息 / 没收到心跳应答，就判定连接已死 */
    private static final int DEAD_FACTOR = 3;
    /** 连接存活探测周期 */
    private static final long WATCHDOG_PERIOD_MS = 5_000L;
    /** 拉 gateway 地址的超时 */
    private static final Duration GATEWAY_TIMEOUT = Duration.ofSeconds(10);
    /** TCP 建连超时：避免连不上时一直卡在"建连中"，导致存活探测无法接手 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** WebSocket 握手（HTTP 升级响应）超时，同上 */
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(20);
    /** 最大重连退避间隔，避免无限增长 */
    private static final long MAX_BACKOFF_MS = 60_000L;

    private final QQBotProperties props;
    private final EventHandler eventHandler;
    private final AccessTokenProvider tokenProvider;
    private final ReactorNettyWebSocketClient wsClient;
    private final ObjectMapper objectMapper;
    /** 调度器在 stop() 后会关闭，start() 时按需重建，保证可重复启停 */
    private volatile ScheduledExecutorService scheduler;

    private volatile WebSocketSession currentSession;
    /** 出站消息队列，所有下发消息（Identify / Resume / 心跳）都走这里 */
    private volatile Sinks.Many<String> outbound;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile ScheduledFuture<?> watchdogFuture;
    private volatile Long heartbeatIntervalMs;
    private volatile Long lastSeq;
    private volatile boolean running;
    private volatile String sessionId;

    /** 建立连接的动作是否正在进行（防止并发重复建连） */
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    /** 是否已排队了一个重连任务（防止重复排队） */
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

    private volatile long lastInboundAt = System.currentTimeMillis();
    private volatile long lastHeartbeatAckAt;
    /** 连续重连次数，连上 READY 后清零，用于指数退避 */
    private volatile int reconnectAttempts;

    public QQBotWebSocketClient(QQBotProperties props,
                                EventHandler eventHandler,
                                AccessTokenProvider tokenProvider) {
        this.props = props;
        this.eventHandler = eventHandler;
        this.tokenProvider = tokenProvider;
        // 显式配置 TCP 建连与握手超时：默认没有超时时，连不上会一直挂在"建连中"，
        // 存活探测看到 connecting=true 不敢接手，结果就是永久离线
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(HANDSHAKE_TIMEOUT);
        this.wsClient = new ReactorNettyWebSocketClient(httpClient);
        this.scheduler = createScheduler();
        // 复用统一配置的 ObjectMapper（忽略未知字段，兼容平台新增字段）
        this.objectMapper = JsonUtils.mapper();
    }

    private ScheduledExecutorService createScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qqbot-gateway-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** stop() 会关闭调度器，这里按需重建，保证 start() 可重复调用 */
    private void ensureScheduler() {
        synchronized (this) {
            if (scheduler == null || scheduler.isShutdown()) {
                scheduler = createScheduler();
            }
        }
    }

    public void start() {
        if (props.getAppId() == null || props.getAppSecret() == null
                || props.getAppId().isBlank() || props.getAppSecret().isBlank()) {
            log.error("app-id / app-secret 不能为空，放弃启动");
            return;
        }
        running = true;
        ensureScheduler();
        startWatchdog();
        doConnect();
    }

    /**
     * 一次连接尝试：拉 gateway 地址 → 建立 WebSocket。
     * 这里刻意不吞异常：任何一步失败都必须走到 scheduleReconnect()，
     * 否则一次瞬时故障就会导致进程活着但永久离线（旧实现的缺陷）。
     */
    private void doConnect() {
        if (!running) {
            return;
        }
        if (!connecting.compareAndSet(false, true)) {
            log.debug("已有连接尝试在进行中，跳过本次");
            return;
        }
        fetchGatewayUrl().subscribe(
                gatewayUrl -> {
                    if (gatewayUrl == null || gatewayUrl.isBlank()) {
                        log.warn("gateway 地址为空，稍后重连");
                        connecting.set(false);
                        scheduleReconnect();
                        return;
                    }
                    try {
                        connect(gatewayUrl);
                    } catch (Exception ex) {
                        log.error("建立 WebSocket 连接失败：{}", ex.getMessage());
                        connecting.set(false);
                        scheduleReconnect();
                    }
                },
                err -> {
                    log.error("获取 gateway 失败：{}", err.getMessage());
                    connecting.set(false);
                    scheduleReconnect();
                }
        );
    }

    private Mono<String> fetchGatewayUrl() {
        return tokenProvider.getToken()
                .switchIfEmpty(Mono.error(new IllegalStateException("access_token 为空")))
                .flatMap(token -> WebClient.builder()
                        .baseUrl("https://api.sgroup.qq.com")
                        .build()
                        .get()
                        .uri("/gateway")
                        .header("Authorization", "QQBot " + token)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .map(m -> (String) m.get("url"))
                        .switchIfEmpty(Mono.error(new IllegalStateException("gateway 响应缺少 url")))
                        .timeout(GATEWAY_TIMEOUT));
    }

    private void connect(String wsUrl) {
        URI uri = URI.create(wsUrl);
        // 每条连接独占一个出站队列；doFinally 用它做身份判断，
        // 避免旧连接的收尾动作把新连接的 outbound / 心跳清掉（重启或快速重连时会踩到）
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        wsClient.execute(uri, new org.springframework.http.HttpHeaders(), session -> {
                    log.info("WebSocket 握手成功，gateway = {}", wsUrl);
                    // 握手成功才算真正连上，此刻复位建连标记，允许后续重连
                    connecting.set(false);
                    this.currentSession = session;
                    this.outbound = sink;
                    this.lastInboundAt = System.currentTimeMillis();
                    this.lastHeartbeatAckAt = 0L;

                    // 出站：把队列里的文本帧持续发送出去
                    Mono<Void> sendMono = session.send(sink.asFlux().map(session::textMessage));

                    // 入站：必须真正订阅，否则收不到 Hello / READY / 消息事件
                    Flux<WebSocketMessage> inbound = session.receive()
                            .doOnNext(this::handleGatewayMessage)
                            .doOnError(ex -> log.error("消息接收流异常：{}", ex.toString()));

                    // 连接关闭时结束出站队列，让 sendMono 正常完成
                    return Mono.when(
                            inbound.then().doFinally(signal -> sink.tryEmitComplete()),
                            sendMono
                    );
                })
                .doFinally(signal -> {
                    if (this.outbound != sink) {
                        // 该连接已被新连接取代，收尾不能影响新连接的会话/心跳状态
                        log.info("旧 WebSocket 连接结束，信号={}（已忽略清理）", signal);
                        return;
                    }
                    log.warn("WebSocket 连接结束，信号={}", signal);
                    this.currentSession = null;
                    this.outbound = null;
                    stopHeartbeat();
                    connecting.set(false);
                    if (running) {
                        scheduleReconnect();
                    }
                })
                .subscribe(
                        unused -> {
                        },
                        err -> log.error("WebSocket 连接异常，准备重连：{}", err.toString())
                );
    }

    private void handleGatewayMessage(WebSocketMessage message) {
        this.lastInboundAt = System.currentTimeMillis();
        String raw = message.getPayloadAsText();
        BotEvent evt;
        try {
            evt = objectMapper.readValue(raw, BotEvent.class);
        } catch (JsonProcessingException e) {
            log.error("解析网关消息失败，原始报文：{}", raw, e);
            return;
        }

        if (evt.getS() != null) {
            lastSeq = evt.getS();
        }

        // 单个事件处理失败（例如业务监听器抛异常）不能把整条接收流打断，
        // 否则 doOnError 会把连接当成故障而断开重连，一条坏消息就能拖垮连接
        try {
            eventHandler.onRawEvent(evt);
        } catch (Exception ex) {
            log.error("处理网关事件失败，t={}，s={}", evt.getT(), evt.getS(), ex);
        }

        if (evt.getOp() == null) {
            return;
        }
        switch (evt.getOp()) {
            // Hello：网关下发第一条消息，携带心跳间隔
            case 10 -> handleHello(evt);
            // 服务端要求重连
            case 7 -> {
                log.warn("收到 RECONNECT，服务端要求重新连接");
                reconnectNow("服务端 RECONNECT");
            }
            // 鉴权失败 / 会话失效：必须清空会话标识并重新 Identify
            case 9 -> {
                log.warn("收到 INVALID_SESSION，清空 session_id 与 seq，重新鉴权");
                sessionId = null;
                lastSeq = null;
                reconnectNow("INVALID_SESSION");
            }
            // 心跳应答
            case 11 -> this.lastHeartbeatAckAt = System.currentTimeMillis();
            case 0 -> handleDispatch(evt);
            default -> {
            }
        }
    }

    private void handleHello(BotEvent evt) {
        if (!(evt.getD() instanceof Map<?, ?> d)) {
            log.warn("Hello 事件缺少 d 字段，无法获取心跳间隔");
            return;
        }
        Object interval = d.get("heartbeat_interval");
        if (interval instanceof Number number) {
            heartbeatIntervalMs = number.longValue();
        }
        log.info("收到 Hello，heartbeat_interval = {} ms", heartbeatIntervalMs);

        if (sessionId != null && lastSeq != null) {
            sendResume();
        } else {
            sendIdentify();
        }
        startHeartbeat();
    }

    private void handleDispatch(BotEvent evt) {
        if ("READY".equals(evt.getT()) && evt.getD() instanceof Map<?, ?> d) {
            sessionId = (String) d.get("session_id");
            reconnectAttempts = 0;
            log.info("收到 READY，session_id = {}，机器人已上线", sessionId);
        } else if ("RESUMED".equals(evt.getT())) {
            reconnectAttempts = 0;
            log.info("会话恢复成功，session_id = {}", sessionId);
        }
    }

    private void sendIdentify() {
        tokenProvider.getToken()
                .switchIfEmpty(Mono.error(new IllegalStateException("access_token 为空")))
                .subscribe(token -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("op", 2);
                    Map<String, Object> d = new HashMap<>();
                    d.put("token", "QQBot " + token);
                    // intents：默认 GROUP_AND_C2C_EVENT (1 << 25)
                    // 该位同时覆盖 GROUP_AT_MESSAGE_CREATE、GROUP_MESSAGE_CREATE、C2C_MESSAGE_CREATE
                    long intents = props.getIntents();
                    d.put("intents", intents);
                    d.put("shard", new Object[]{0, 1});
                    payload.put("d", d);
                    sendJson(payload);
                    log.info("已发送 Identify，intents = {}", intents);
                }, err -> {
                    log.error("获取 token 用于 Identify 失败：{}", err.getMessage());
                    // 鉴权发不出去这条连接就是废的，直接重建
                    reconnectNow("Identify 获取 token 失败");
                });
    }

    private void sendResume() {
        tokenProvider.getToken()
                .switchIfEmpty(Mono.error(new IllegalStateException("access_token 为空")))
                .subscribe(token -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("op", 6);
                    Map<String, Object> d = new HashMap<>();
                    d.put("token", "QQBot " + token);
                    d.put("session_id", sessionId);
                    d.put("seq", lastSeq);
                    payload.put("d", d);
                    sendJson(payload);
                    log.info("已发送 Resume，尝试恢复会话");
                }, err -> {
                    log.error("获取 token 用于 Resume 失败：{}", err.getMessage());
                    sessionId = null;
                    lastSeq = null;
                    reconnectNow("Resume 获取 token 失败");
                });
    }

    private void startHeartbeat() {
        stopHeartbeat();
        Long interval = heartbeatIntervalMs;
        if (interval == null || interval <= 0) {
            return;
        }
        heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            if (!running) {
                return;
            }
            Map<String, Object> hb = new HashMap<>();
            hb.put("op", 1);
            hb.put("d", lastSeq);
            sendJson(hb);
        }, interval, interval, TimeUnit.MILLISECONDS);
        log.info("心跳已启动，间隔 {} ms", interval);
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> future = heartbeatFuture;
        if (future != null) {
            future.cancel(false);
            heartbeatFuture = null;
        }
    }

    /**
     * 连接存活探测：
     * 1）本地没有活动会话、也没有排队重连 → 补一次重连；
     * 2）超过心跳间隔 3 倍没有任何入站消息 → 判定假死（半开连接收不到任何错误）强制重连；
     * 3）心跳发出后超过 2 个间隔没有 op=11 应答 → 同样判定假死。
     */
    private void startWatchdog() {
        ScheduledFuture<?> existing = watchdogFuture;
        if (existing != null && !existing.isCancelled()) {
            return;
        }
        try {
            watchdogFuture = scheduler.scheduleAtFixedRate(this::checkLiveness,
                    WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
            log.info("连接存活探测已启动，周期 {} ms", WATCHDOG_PERIOD_MS);
        } catch (RejectedExecutionException ex) {
            log.warn("调度器已关闭，存活探测未启动");
        }
    }

    private void checkLiveness() {
        if (!running) {
            return;
        }
        if (currentSession == null) {
            if (connecting.get() || reconnectScheduled.get()) {
                return;
            }
            log.warn("当前没有活动连接且没有待执行的重连，主动触发重连");
            scheduleReconnect();
            return;
        }
        Long interval = heartbeatIntervalMs;
        if (interval == null || interval <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long sinceInbound = now - lastInboundAt;
        long ackRef = lastHeartbeatAckAt > 0 ? lastHeartbeatAckAt : lastInboundAt;
        long sinceAck = now - ackRef;
        if (sinceInbound > interval * DEAD_FACTOR) {
            log.warn("已 {} ms 未收到任何网关消息（心跳间隔 {} ms），判定连接已断开，强制重连",
                    sinceInbound, interval);
            reconnectNow("连接长时间无入站消息");
        } else if (sinceAck > interval * 2) {
            log.warn("已 {} ms 未收到心跳应答 op=11（心跳间隔 {} ms），判定连接已断开，强制重连",
                    sinceAck, interval);
            reconnectNow("心跳应答超时");
        }
    }

    private void sendJson(Object obj) {
        Sinks.Many<String> sink = this.outbound;
        if (sink == null) {
            log.warn("WebSocket 尚未就绪，丢弃待发送消息：{}", obj);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(obj);
            Sinks.EmitResult result = sink.tryEmitNext(json);
            if (result.isFailure()) {
                log.warn("发送网关消息失败（{}），内容：{}", result, json);
            }
        } catch (JsonProcessingException e) {
            log.error("序列化消息失败", e);
        }
    }

    /**
     * 主动断开当前连接并安排重连。关闭动作会触发 doFinally，其中的 scheduleReconnect()
     * 与这里的调用由 reconnectScheduled 去重，不会重复建连。
     */
    private void reconnectNow(String reason) {
        log.warn("触发重连，原因：{}", reason);
        WebSocketSession sess = currentSession;
        if (sess != null && sess.isOpen()) {
            sess.close().subscribe(null, ex -> log.debug("关闭旧连接时异常：{}", ex.toString()));
        }
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        if (!reconnectScheduled.compareAndSet(false, true)) {
            log.debug("已有重连任务在排队，忽略重复请求");
            return;
        }
        long delay = nextBackoffMs();
        int attempt = reconnectAttempts + 1;
        reconnectAttempts = attempt;
        log.warn("{} ms 后执行第 {} 次重连", delay, attempt);
        try {
            scheduler.schedule(() -> {
                reconnectScheduled.set(false);
                if (!running) {
                    return;
                }
                // 旧连接的 doFinally 可能迟迟不到（半开连接关闭无响应），这里强制复位建连标记，保证重连一定能推进
                connecting.set(false);
                log.info("开始第 {} 次重连", attempt);
                doConnect();
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            // 已 stop()、调度器关闭：撤销排队标记，避免永久卡在"已有重连计划"
            reconnectScheduled.set(false);
            log.warn("调度器已关闭，取消本次重连");
        }
    }

    /** 指数退避：base * 2^(attempt-1)，上限 60 秒 */
    private long nextBackoffMs() {
        long base = Math.max(1_000L, props.getReconnectDelayMs());
        int shift = Math.min(Math.max(reconnectAttempts, 0), 8);
        long delay = base * (1L << shift);
        return Math.min(delay, MAX_BACKOFF_MS);
    }

    public void stop() {
        running = false;
        // 允许后续 start() 重新启动：复位排队标记与建连标记
        reconnectScheduled.set(false);
        connecting.set(false);
        stopHeartbeat();
        ScheduledFuture<?> watchdog = watchdogFuture;
        if (watchdog != null) {
            watchdog.cancel(false);
            watchdogFuture = null;
        }
        Sinks.Many<String> sink = this.outbound;
        if (sink != null) {
            sink.tryEmitComplete();
        }
        WebSocketSession sess = currentSession;
        if (sess != null && sess.isOpen()) {
            sess.close().subscribe(null, ex -> log.debug("关闭连接时异常：{}", ex.toString()));
        }
        ScheduledExecutorService current = scheduler;
        if (current != null) {
            current.shutdown();
        }
    }
}
