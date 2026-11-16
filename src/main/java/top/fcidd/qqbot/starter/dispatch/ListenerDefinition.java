package top.fcidd.qqbot.starter.dispatch;

import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

/**
 * 一个被注解声明的消息处理方法的描述。
 */
public record ListenerDefinition(Object bean,
                                 Method method,
                                 MessageEventType type,
                                 String command,
                                 boolean ignoreCase) {

    public boolean matches(String content) {
        return CommandMatcher.matches(content, command, ignoreCase);
    }

    public String extractArgs(String content) {
        return CommandMatcher.extractArgs(content, command, ignoreCase);
    }

    public String describe() {
        return ClassUtils.getUserClass(bean).getSimpleName() + "#" + method.getName();
    }
}
