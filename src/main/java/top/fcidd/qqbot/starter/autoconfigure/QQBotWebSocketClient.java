package top.fcidd.qqbot.starter.autoconfigure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import top.fcidd.qqbot.starter.event.BotEvent;
import top.fcidd.qqbot.starter.event.EventHandler;
import top.fcidd.qqbot.starter.model.IdentifyPayload;
import top.fcidd.qqbot.starter.util.JsonUtils;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Log4j2
public class QQBotWebSocketClient {

    private final QQBotProperties props;
    private final EventHandler eventHandler;
    private final AccessTokenProvider tokenProvider;
    private final ReactorNettyWebSocketClient wsClient;
    private final ScheduledExecutorService scheduler;
    private final ObjectMapper objectMapper;

    private volatile org.springframework.web.reactive.socket.WebSocketSession currentSession;
    private Long heartbeatIntervalMs;
    private Long lastSeq;
    private boolean running;
    private String sessionId;

    public QQBotWebSocketClient(QQBotProperties props,
                                EventHandler eventHandler,
                                AccessTokenProvider tokenProvider) {
        this.props = props;
        this.eventHandler = eventHandler;
        this.tokenProvider = tokenProvider;
        this.wsClient = new ReactorNettyWebSocketClient();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.objectMapper = new ObjectMapper();
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
                    log.info("使用鉴权头 = [{}]", auth);

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

            Flux<WebSocketMessage> inbound = session.receive()
                    .doOnNext(msg -> {
                        String raw = msg.getPayloadAsText();
                        try {
                            BotEvent evt = objectMapper.readValue(raw, BotEvent.class);
                            eventHandler.onRawEvent(evt);

                            if (evt.getS() != null) {
                                lastSeq = evt.getS();
                            }

                            // Hello
                            if (evt.getOp() == 10) {
                                Map<String, Object> d = (Map<String, Object>) evt.getD();
                                heartbeatIntervalMs = ((Number) d.get("heartbeat_interval")).longValue();
                                if (sessionId != null && lastSeq != null) {
                                    sendResume(session);
                                } else {
                                    sendIdentify(session);
                                }
                                startHeartbeat(session);
                            }

                            // READY
                            if (evt.getOp() == 0 && "READY".equals(evt.getT())) {
                                Map<String, Object> d = (Map<String, Object>) evt.getD();
                                sessionId = (String) d.get("session_id");
                                log.info("收到 READY，session_id = {}", sessionId);
                            }

                            // Invalid Session
                            if (evt.getOp() == 9) {
                                log.warn("收到 INVALID_SESSION，清空会话标识，准备重连");
                                sessionId = null;
                                lastSeq = null;
                            }

                        } catch (JsonProcessingException e) {
                            log.error("解析网关消息失败，原始报文：{}", raw, e);
                        }
                    })
                    .doOnError(ex -> log.error("消息接收流异常", ex))
                    .doFinally(signal -> {
                        log.warn("连接流结束，信号：{}", signal);
                        this.currentSession = null;
                        if (running) {
                            scheduleReconnect();
                        }
                    });

            return Mono.never().thenMany(inbound).then();
        }).subscribe(
                unused -> {},
                err -> {
                    log.error("WebSocket execute 异常，准备重连：", err);
                    scheduleReconnect();
                }
        );
    }

    private void sendIdentify(org.springframework.web.reactive.socket.WebSocketSession session) {
        tokenProvider.getToken()
                .subscribe(token -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("op", 2);
                    Map<String, Object> d = new HashMap<>();
                    d.put("token", "QQBot " + token);
                    // intents：频道消息 + @消息 + 私信
                    int intents = (1 << 0) | (1 << 12) | (1 << 30);
                    d.put("intents", intents);
                    d.put("shard", new Integer[]{0, 1});
                    payload.put("d", d);
                    sendJson(session, payload);
                    log.info("已发送 Identify");
                }, err -> log.error("获取 token 用于 Identify 失败", err));
    }

    private void sendResume(org.springframework.web.reactive.socket.WebSocketSession session) {
        tokenProvider.getToken()
                .subscribe(token -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("op", 6);
                    Map<String, Object> d = new HashMap<>();
                    d.put("token", "QQBot " + token);
                    d.put("session_id", sessionId);
                    d.put("seq", lastSeq);
                    payload.put("d", d);
                    sendJson(session, payload);
                    log.info("已发送 Resume，尝试恢复会话");
                }, err -> log.error("获取 token 用于 Resume 失败", err));
    }

    private void startHeartbeat(org.springframework.web.reactive.socket.WebSocketSession session) {
        scheduler.scheduleAtFixedRate(() -> {
            if (!running || !session.isOpen()) {
                return;
            }
            Map<String, Object> hb = new HashMap<>();
            hb.put("op", 1);
            hb.put("d", lastSeq);
            sendJson(session, hb);
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void sendJson(org.springframework.web.reactive.socket.WebSocketSession session, Object obj) {
        try {
            String json = objectMapper.writeValueAsString(obj);
            session.send(Mono.just(session.textMessage(json)))
                    .subscribe(
                            unused -> {},
                            err -> log.error("发送网关消息失败", err)
                    );
        } catch (JsonProcessingException e) {
            log.error("序列化消息失败", e);
        }
    }

    private void scheduleReconnect() {
        if (!running) return;
        scheduler.schedule(this::start, props.getReconnectDelayMs(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
        var sess = currentSession;
        if (sess != null && sess.isOpen()) {
            sess.close().subscribe();
        }
    }
}