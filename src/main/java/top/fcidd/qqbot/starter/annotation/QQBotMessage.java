package top.fcidd.qqbot.starter.annotation;

import top.fcidd.qqbot.starter.dispatch.MessageEventType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 监听 QQ 机器人消息，一个注解覆盖「群聊 @机器人」「群聊全量消息」「单聊」三类场景。
 * <p>
 * 默认三类场景全部生效，等同于同时标注 {@link GroupAtMessage}、{@link GroupMessage}、{@link C2CMessage}；
 * 只需要部分场景时用 {@link #type()} 指定，例如 {@code @QQBotMessage(type = MessageEventType.C2C)}。
 * <p>
 * 只对 {@link QQBotListener} 类中的方法生效。
 *
 * <pre>{@code
 * @QQBotListener
 * public class MyBotListener {
 *
 *     // 群 @机器人、群全量、单聊都回复
 *     @QQBotMessage("/ping")
 *     public String ping() { return "pong"; }
 *
 *     // 只在单聊生效
 *     @QQBotMessage(value = "/help", type = MessageEventType.C2C)
 *     public String help() { return "帮助"; }
 * }
 * }</pre>
 *
 * <p>
 * 注意：三类场景共用同一个消息体 {@code MessagePayload}（字段超集），可用
 * {@code MessagePayload#isGroup()} / {@code #isC2C()} / {@code #sceneLabel()} 自动判断群聊还是单聊，
 * 因此一个带 {@code MessagePayload} 参数的方法可以同时适配三类场景。
 *
 * @see GroupAtMessage
 * @see GroupMessage
 * @see C2CMessage
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface QQBotMessage {

    /** 指令前缀，空字符串表示匹配场景内的全部消息 */
    String value() default "";

    /** 指令匹配是否忽略大小写 */
    boolean ignoreCase() default true;

    /**
     * 生效的消息事件类型，默认三类场景全部生效。
     * <p>
     * 群聊全量消息需要群主在机器人资料页开启「接收所有消息」后平台才会推送。
     */
    MessageEventType[] type() default {
            MessageEventType.GROUP_AT,
            MessageEventType.GROUP_MESSAGE,
            MessageEventType.C2C
    };
}
