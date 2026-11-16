package top.fcidd.qqbot.starter.autoconfigure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import top.fcidd.qqbot.starter.event.BotEvent;
import top.fcidd.qqbot.starter.event.EventHandler;
import top.fcidd.qqbot.starter.util.JsonUtils;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class QQBotWebSocketClient {

    private final QQBotProperties props;
    private final EventHandler eventHandler;
    private final AccessTokenProvider tokenProvider;
    private final ReactorNettyWebSocketClient wsClient;
    private final ScheduledExecutorService scheduler;
    private final ObjectMapper objectMapper;

    private volatile WebSocketSession currentSession;
    /** 出站消息队列，所有下发消息（Identify / Resume / 心跳）都走这里 */
    private volatile Sinks.Many<String> outbound;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile Long heartbeatIntervalMs;
    private volatile Long lastSeq;
    private volatile boolean running;
    private volatile String sessionId;

    public QQBotWebSocketClient(QQBotProperties props,
                                EventHandler eventHandler,
                                AccessTokenProvider tokenProvider) {
        this.props = props;
        this.eventHandler = eventHandler;
        this.tokenProvider = tokenProvider;
        this.wsClient = new ReactorNettyWebSocketClient();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        // 复用统一配置的 ObjectMapper（忽略未知字段，兼容平台新增字段）
        this.objectMapper = JsonUtils.mapper();
    }

    public void start() {
        if (props.getAppId() == null || props.getAppSecret() == null
                || props.getAppId().isBlank() || props.getAppSecret().isBlank()) {
            log.error("app-id / app-secret 不能为空，放弃启动");
            return;
        }
        running = true;
        fetchGatewayUrl()
                .subscribe(
                        gatewayUrl -> {
                            if (gatewayUrl != null && !gatewayUrl.isBlank()) {
                                connect(gatewayUrl);
                            } else {
                                log.warn("gateway 地址为空，稍后重试");
                                scheduleReconnect();
                            }
                        },
                        err -> {
                            log.error("start 获取 gateway 异常：", err);
                            scheduleReconnect();
                        }
                );
    }

    private Mono<String> fetchGatewayUrl() {
        return tokenProvider.getToken()
                .flatMap(token -> {
                    String auth = "QQBot " + token;

                    String baseHost = "https://api.sgroup.qq.com";
                    return WebClient.builder()
                            .baseUrl(baseHost)
                            .build()
                            .get()
                            .uri("/gateway")
                            .header("Authorization", auth)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .map(m -> (String) m.get("url"));
                })
                .onErrorResume(ex -> {
                    log.error("请求 gateway 失败：{}", ex.getMessage());
                    return Mono.empty();
                });
    }

    private void connect(String wsUrl) {
        URI uri = URI.create(wsUrl);
        HttpHeaders headers = new HttpHeaders();

        wsClient.execute(uri, headers, session -> {
                    log.info("WebSocket 握手成功，gateway = {}", wsUrl);
                    this.currentSession = session;

                    Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
                    this.outbound = sink;

                    // 出站：把队列里的文本帧持续发送出去
                    Mono<Void> sendMono = session.send(sink.asFlux().map(session::textMessage));

                    // 入站：必须真正订阅，否则收不到 Hello / READY / 消息事件
                    Flux<WebSocketMessage> inbound = session.receive()
                            .doOnNext(this::handleGatewayMessage)
                            .doOnError(ex -> log.error("消息接收流异常", ex));

                    // 连接关闭时结束出站队列，让 sendMono 正常完成
                    return Mono.when(
                            inbound.then().doFinally(signal -> sink.tryEmitComplete()),
                            sendMono
                    );
                })
                .doFinally(signal -> {
                    log.warn("WebSocket 连接结束，信号={}", signal);
                    this.currentSession = null;
                    this.outbound = null;
                    stopHeartbeat();
                    if (running) {
                        scheduleReconnect();
                    }
                })
                .subscribe(
                        unused -> {
                        },
                        err -> log.error("WebSocket 连接异常，准备重连：", err)
                );
    }

    private void handleGatewayMessage(WebSocketMessage message) {
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

        eventHandler.onRawEvent(evt);

        if (evt.getOp() == null) {
            return;
        }
        switch (evt.getOp()) {
            // Hello：网关下发第一条消息，携带心跳间隔
            case 10 -> handleHello(evt);
            // 服务端要求重连
            case 7 -> {
                log.warn("收到 RECONNECT，服务端要求重新连接");
                reconnectNow();
            }
            // 鉴权失败 / 会话失效
            case 9 -> {
                log.warn("收到 INVALID_SESSION，清空会话标识，重新鉴权");
                sessionId = null;
                lastSeq = null;
            }
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
            log.info("收到 READY，session_id = {}，机器人已上线", sessionId);
        } else if ("RESUMED".equals(evt.getT())) {
            log.info("会话恢复成功，session_id = {}", sessionId);
        }
    }

    private void sendIdentify() {
        tokenProvider.getToken()
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
                }, err -> log.error("获取 token 用于 Identify 失败", err));
    }

    private void sendResume() {
        tokenProvider.getToken()
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
                }, err -> log.error("获取 token 用于 Resume 失败", err));
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

    private void reconnectNow() {
        WebSocketSession sess = currentSession;
        if (sess != null && sess.isOpen()) {
            sess.close().subscribe();
        }
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        scheduler.schedule(this::start, props.getReconnectDelayMs(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;
        stopHeartbeat();
        Sinks.Many<String> sink = this.outbound;
        if (sink != null) {
            sink.tryEmitComplete();
        }
        WebSocketSession sess = currentSession;
        if (sess != null && sess.isOpen()) {
            sess.close().subscribe();
        }
        scheduler.shutdown();
    }
}
