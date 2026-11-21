package top.fcidd.qqbot.starter.demo;

import lombok.extern.log4j.Log4j2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import top.fcidd.qqbot.starter.annotation.QQBotListener;
import top.fcidd.qqbot.starter.annotation.QQBotMessage;
import top.fcidd.qqbot.starter.dispatch.MessageEventType;
import top.fcidd.qqbot.starter.model.message.MessagePayload;

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

    /** 三类场景（群@、群全量、单聊）统一回复 */
    @QQBotMessage("/ping")
    public String ping() {
        log.info("收到 /ping 指令");
        return "pong";
    }

    /** 只在群聊场景生效 */
    @QQBotMessage(value = "/echo", type = {MessageEventType.GROUP_AT, MessageEventType.GROUP_MESSAGE})
    public String echo(String args) {
        return args.isBlank() ? "用法：/echo 内容" : args;
    }

    /** 只在单聊场景生效 */
    @QQBotMessage(type = MessageEventType.C2C)
    public String echoPrivate(MessagePayload message) {
        String content = message.getContent() == null ? "" : message.getContent().trim();
        return "收到：" + content;
    }

    /** 只在群聊全量消息场景生效（需群主开启「接收所有消息」） */
    @QQBotMessage(value = "测试", type = MessageEventType.GROUP_MESSAGE)
    public String groupMessage() {
        return "收到测试";
    }

    /** 统一消息体自动判断场景：群聊 / 单聊都可用 */
    @QQBotMessage("/scene")
    public String scene(MessagePayload message) {
        return "当前场景：" + message.sceneLabel();
    }

    /** 无返回值 */
    @QQBotMessage("/search")
    public void search(String args,MessagePayload messagePayload){
        //可以注入 QQBotMessageSender 主动发送
    }
}
