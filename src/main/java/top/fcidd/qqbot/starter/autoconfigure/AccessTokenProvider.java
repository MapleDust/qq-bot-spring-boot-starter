package top.fcidd.qqbot.starter.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Log4j2
public class AccessTokenProvider {

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);

    private final QQBotProperties props;
    private final WebClient webClient;
    private final ObjectMapper mapper;

    private volatile String accessToken;
    private volatile Instant expireAt;

    /** 正在进行的刷新请求，多个并发调用共享同一次请求，避免重复打 token 接口 */
    private volatile Mono<String> inflight;

    public AccessTokenProvider(QQBotProperties props) {
        this.props = props;
        this.webClient = WebClient.create("https://api.bot.qq.com");
        this.mapper = new ObjectMapper();
    }

    /**
     * 获取 access_token。失败会把异常抛给调用方，不再静默返回空 Mono——
     * 静默失败会让重连链路断掉（"进程活着但一直离线"）。
     */
    public Mono<String> getToken() {
        String cached = validToken();
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.defer(() -> {
            String again = validToken();
            if (again != null) {
                return Mono.just(again);
            }
            Mono<String> shared;
            synchronized (this) {
                if (inflight == null) {
                    inflight = fetchNewToken()
                            .doOnNext(resp -> {
                                accessToken = resp.getAccessToken();
                                expireAt = Instant.now().plusSeconds(resp.getExpiresIn());
                                log.info("access_token 获取成功，expires_in = {} s", resp.getExpiresIn());
                            })
                            .map(TokenResp::getAccessToken)
                            .doFinally(signal -> {
                                synchronized (this) {
                                    inflight = null;
                                }
                            })
                            .cache();
                }
                shared = inflight;
            }
            return shared;
        });
    }

    private String validToken() {
        String token = accessToken;
        Instant expire = expireAt;
        if (token != null && expire != null
                && expire.toEpochMilli() - System.currentTimeMillis() > props.getTokenRefreshAheadMs()) {
            return token;
        }
        return null;
    }

    private Mono<TokenResp> fetchNewToken() {
        Map<String, String> body = Map.of(
                "appId", props.getAppId(),
                "clientSecret", props.getAppSecret()
        );
        return webClient.post()
                .uri("/app/getAppAccessToken")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .defaultIfEmpty("")
                .timeout(FETCH_TIMEOUT)
                // token 接口鉴权失败时仍返回 200 + 错误体，必须校验响应内容，不能当成成功
                .map(this::parseAndValidate)
                .doOnError(ex -> log.error("获取 access_token 失败：{}", ex.getMessage()));
    }

    private TokenResp parseAndValidate(String raw) {
        TokenResp resp;
        try {
            resp = mapper.readValue(raw, TokenResp.class);
        } catch (Exception e) {
            throw new IllegalStateException("解析 access_token 响应失败，响应=" + raw, e);
        }
        if (resp == null || resp.getAccessToken() == null || resp.getAccessToken().isBlank()) {
            throw new IllegalStateException("获取 access_token 被拒绝，响应=" + raw);
        }
        if (resp.getExpiresIn() <= 0) {
            throw new IllegalStateException("access_token 过期时间无效，响应=" + raw);
        }
        return resp;
    }

    @Data
    public static class TokenResp {
        private String access_token;
        private long expires_in;

        public String getAccessToken() {
            return access_token;
        }

        public long getExpiresIn() {
            return expires_in;
        }
    }
}
