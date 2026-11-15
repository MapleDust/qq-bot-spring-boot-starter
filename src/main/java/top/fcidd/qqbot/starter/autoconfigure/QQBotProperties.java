package top.fcidd.qqbot.starter.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static reactor.netty.http.HttpConnectionLiveness.log;

@Data
@ConfigurationProperties(prefix = "qq.bot")
public class QQBotProperties {
    private String appId;
    private String appSecret;
    private boolean autoStart = true;
    private long reconnectDelayMs = 3000;
    // 提前多久刷新 token，默认提前 60 秒
    private long tokenRefreshAheadMs = 60_000;
}