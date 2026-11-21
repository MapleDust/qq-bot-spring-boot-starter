package top.fcidd.qqbot.starter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 监听群聊中 @机器人 的消息（事件 GROUP_AT_MESSAGE_CREATE）。
 * <p>
 * 只对 {@link QQBotListener} 类中的方法生效。
 *
 * <pre>{@code
 * // 匹配所有群 @消息
 * @GroupAtMessage
 * public void onAny(MessagePayload msg) { ... }
 *
 * // 只匹配 /help 指令
 * @GroupAtMessage("/help")
 * public String help() { return "帮助：..."; }
 * }</pre>
 *
 * @deprecated 三类消息场景已合并到 {@link QQBotMessage}，新代码请直接使用
 * {@code @QQBotMessage}（默认同时覆盖群@、群全量、单聊），本注解仅为向后兼容保留。
 */
@Deprecated
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface GroupAtMessage {

    /**
     * 指令前缀，空字符串表示匹配该场景下的全部消息。
     * 匹配时忽略首尾空格，并与后面的内容做边界判断（/ping 不会误匹配 /pingpong）。
     */
    String value() default "";

    /** 指令匹配是否忽略大小写 */
    boolean ignoreCase() default true;
}
