package top.fcidd.qqbot.starter.dispatch;

import lombok.extern.log4j.Log4j2;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import top.fcidd.qqbot.starter.annotation.C2CMessage;
import top.fcidd.qqbot.starter.annotation.GroupAtMessage;
import top.fcidd.qqbot.starter.annotation.GroupMessage;
import top.fcidd.qqbot.starter.annotation.QQBotListener;
import top.fcidd.qqbot.starter.event.BotEvent;
import top.fcidd.qqbot.starter.model.message.C2CMessagePayload;
import top.fcidd.qqbot.starter.model.message.GroupMessagePayload;
import top.fcidd.qqbot.starter.sender.QQBotMessageSender;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描 {@link QQBotListener} 类中被注解标记的方法，构建消息事件 -> 处理器映射表。
 * 首次投递事件时惰性初始化（此时容器已完全就绪）。
 */
@Log4j2
public class MessageHandlerRegistry {

    private final ApplicationContext applicationContext;
    private volatile Map<MessageEventType, List<ListenerDefinition>> table;

    public MessageHandlerRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public List<ListenerDefinition> handlers(MessageEventType type) {
        Map<MessageEventType, List<ListenerDefinition>> snapshot = table;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = table;
                if (snapshot == null) {
                    snapshot = scan();
                    table = snapshot;
                }
            }
        }
        return snapshot.getOrDefault(type, List.of());
    }

    /**
     * 清空缓存，下次投递时重新扫描（运行期动态注册监听器后可调用）。
     */
    public void reload() {
        table = null;
    }

    private Map<MessageEventType, List<ListenerDefinition>> scan() {
        Map<MessageEventType, List<ListenerDefinition>> result = new EnumMap<>(MessageEventType.class);
        for (MessageEventType type : MessageEventType.values()) {
            result.put(type, new ArrayList<>());
        }

        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(QQBotListener.class);
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            Class<?> userClass = ClassUtils.getUserClass(bean);
            // 包含继承方法与非 public 方法，监听方法可以是 private / 包级私有
            Method[] methods = ReflectionUtils.getAllDeclaredMethods(userClass);
            for (Method method : methods) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                GroupAtMessage groupAt = AnnotatedElementUtils.findMergedAnnotation(method, GroupAtMessage.class);
                if (groupAt != null) {
                    register(result, bean, method, MessageEventType.GROUP_AT, groupAt.value(), groupAt.ignoreCase());
                    continue;
                }
                GroupMessage groupAll = AnnotatedElementUtils.findMergedAnnotation(method, GroupMessage.class);
                if (groupAll != null) {
                    register(result, bean, method, MessageEventType.GROUP_MESSAGE, groupAll.value(), groupAll.ignoreCase());
                    continue;
                }
                C2CMessage c2c = AnnotatedElementUtils.findMergedAnnotation(method, C2CMessage.class);
                if (c2c != null) {
                    register(result, bean, method, MessageEventType.C2C, c2c.value(), c2c.ignoreCase());
                }
            }
        }

        int total = result.values().stream().mapToInt(List::size).sum();
        log.info("已注册消息监听处理器 {} 个：群@={}，群全量={}，单聊={}",
                total,
                result.get(MessageEventType.GROUP_AT).size(),
                result.get(MessageEventType.GROUP_MESSAGE).size(),
                result.get(MessageEventType.C2C).size());
        return Collections.unmodifiableMap(result);
    }

    private void register(Map<MessageEventType, List<ListenerDefinition>> result,
                          Object bean,
                          Method method,
                          MessageEventType type,
                          String command,
                          boolean ignoreCase) {
        if (!isSupported(method, type)) {
            log.warn("忽略不支持的监听方法 {}：参数类型只支持 {}、BotEvent、QQBotMessageSender、String",
                    userClassName(bean) + "#" + method.getName(),
                    type.isGroup() ? GroupMessagePayload.class.getSimpleName() : C2CMessagePayload.class.getSimpleName());
            return;
        }
        // 无参方法、返回值类型不做限制；返回 String 时会被当作回复内容自动发送
        ReflectionUtils.makeAccessible(method);
        result.get(type).add(new ListenerDefinition(bean, method, type, command == null ? "" : command.trim(), ignoreCase));
    }

    private boolean isSupported(Method method, MessageEventType type) {
        Class<?> payloadType = type.isGroup() ? GroupMessagePayload.class : C2CMessagePayload.class;
        for (Class<?> paramType : method.getParameterTypes()) {
            if (paramType.isPrimitive()) {
                return false;
            }
            boolean supported = payloadType.isAssignableFrom(paramType)
                    || paramType.isAssignableFrom(payloadType)
                    || BotEvent.class.isAssignableFrom(paramType)
                    || QQBotMessageSender.class.isAssignableFrom(paramType)
                    || String.class.equals(paramType);
            if (!supported) {
                return false;
            }
        }
        return true;
    }

    private String userClassName(Object bean) {
        return ClassUtils.getUserClass(bean).getSimpleName();
    }
}
