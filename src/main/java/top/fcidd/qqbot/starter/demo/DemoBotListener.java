package top.fcidd.qqbot.starter.demo;

import lombok.extern.log4j.Log4j2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import top.fcidd.qqbot.starter.annotation.C2CMessage;
import top.fcidd.qqbot.starter.annotation.GroupAtMessage;
import top.fcidd.qqbot.starter.annotation.GroupMessage;
import top.fcidd.qqbot.starter.annotation.QQBotListener;
import top.fcidd.qqbot.starter.model.message.C2CMessagePayload;

/**
 * 演示用监听器，仅在 {@code qq.bot.demo-listener=true} 时启用。
 * <p>
 * 群内 @机器人 发送 {@code /ping} 回复 {@code pong}；
 * 私聊消息原样回显，用于验证「注解监听 + 自动回复」链路。
 */
@Log4j2
@QQBotListener
@ConditionalOnProperty(prefix = "qq.bot", name = "demo-listener", havingValue = "true")
public class DemoBotListener {

    @GroupAtMessage("/ping")
    public String ping() {
        log.info("收到 /ping 指令");
        return "pong";
    }

    @GroupAtMessage("/echo")
    public String echo(String args) {
        return args.isBlank() ? "用法：/echo 内容" : args;
    }

    @C2CMessage
    public String echoPrivate(C2CMessagePayload message) {
        String content = message.getContent() == null ? "" : message.getContent().trim();
        return "收到：" + content;
    }

    @GroupMessage("测试")
    public String groupMessage() {
        return "收到测试";
    }
}
