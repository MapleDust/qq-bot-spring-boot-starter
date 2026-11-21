package top.fcidd.qqbot.starter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 监听单聊消息（事件 C2C_MESSAGE_CREATE）。
 * <p>
 * 只对 {@link QQBotListener} 类中的方法生效。
 *
 * @deprecated 三类消息场景已合并到 {@link QQBotMessage}，新代码请直接使用
 * {@code @QQBotMessage}（默认同时覆盖群@、群全量、单聊），本注解仅为向后兼容保留。
 */
@Deprecated
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface C2CMessage {

    /** 指令前缀，空字符串表示匹配全部单聊消息 */
    String value() default "";

    /** 指令匹配是否忽略大小写 */
    boolean ignoreCase() default true;
}
