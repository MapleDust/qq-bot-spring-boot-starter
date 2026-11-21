package top.fcidd.qqbot.starter.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个类为 QQ 机器人消息监听器。
 * <p>
 * 被标记的类会被 Spring 当作组件扫描注册，类中的 {@link QQBotMessage} 方法会在收到对应消息时被调用。
 * 旧的 {@link GroupAtMessage}、{@link GroupMessage}、{@link C2CMessage} 仍可用，但已废弃。
 *
 * <pre>{@code
 * @QQBotListener
 * public class MyListener {
 *     @QQBotMessage("/ping")
 *     public String ping() { return "pong"; }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface QQBotListener {
}
