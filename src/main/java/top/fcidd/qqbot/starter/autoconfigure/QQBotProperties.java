package top.fcidd.qqbot.starter.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "qq.bot")
public class QQBotProperties {
    private String appId;
    private String appSecret;
    private boolean autoStart = true;
    private long reconnectDelayMs = 3000;
    /**
     * 事件订阅 intents 位掩码，默认 GROUP_AND_C2C_EVENT (1 << 25)。
     * 该位覆盖单聊消息 C2C_MESSAGE_CREATE、群聊@机器人 GROUP_AT_MESSAGE_CREATE、
     * 群聊全量消息 GROUP_MESSAGE_CREATE，如需订阅频道等其它事件按位或叠加即可。
     */
    private long intents = 1L << 25;
    // 提前多久刷新 token，默认提前 60 秒
    private long tokenRefreshAheadMs = 60_000;
}