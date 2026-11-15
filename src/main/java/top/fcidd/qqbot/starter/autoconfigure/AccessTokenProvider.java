package top.fcidd.qqbot.starter.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@Log4j2
public class AccessTokenProvider {

    private final QQBotProperties props;
    private final WebClient webClient;
    private final ObjectMapper mapper;

    private volatile String accessToken;
    private volatile Instant expireAt;

    public AccessTokenProvider(QQBotProperties props) {
        this.props = props;
        this.webClient = WebClient.create("https://api.bot.qq.com");
        this.mapper = new ObjectMapper();
    }

    public Mono<String> getToken() {
        if (accessToken != null && expireAt != null) {
            long remainMs = expireAt.toEpochMilli() - System.currentTimeMillis();
            if (remainMs > props.getTokenRefreshAheadMs()) {
                return Mono.just(accessToken);
            }
        }
        return fetchNewToken()
                .doOnSuccess(resp -> {
                    accessToken = resp.getAccessToken();
                    expireAt = Instant.now().plusSeconds(resp.getExpiresIn());
                    log.info("access_token 获取成功，expires_in = {} s", resp.getExpiresIn());
                })
                .map(TokenResp::getAccessToken);
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
                .bodyToMono(TokenResp.class)
                .onErrorResume(ex -> {
                    log.error("获取 access_token 失败: {}", ex.getMessage());
                    return Mono.empty();
                });
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
