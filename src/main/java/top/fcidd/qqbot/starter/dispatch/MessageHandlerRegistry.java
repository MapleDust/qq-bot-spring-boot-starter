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
import top.fcidd.qqbot.starter.annotation.QQBotMessage;
import top.fcidd.qqbot.starter.event.BotEvent;
import top.fcidd.qqbot.starter.model.message.MessagePayload;
import top.fcidd.qqbot.starter.sender.QQBotMessageSender;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
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

    @SuppressWarnings("deprecation") // 兼容合并前的老注解，见下方扫描逻辑
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
                QQBotMessage unified = AnnotatedElementUtils.findMergedAnnotation(method, QQBotMessage.class);
                if (unified != null) {
                    registerUnified(result, bean, method, unified);
                    continue;
                }
                // 兼容合并前的老注解：允许一个方法同时标注多个，按实际标注的场景全部注册
                GroupAtMessage groupAt = AnnotatedElementUtils.findMergedAnnotation(method, GroupAtMessage.class);
                if (groupAt != null) {
                    register(result, bean, method, MessageEventType.GROUP_AT, groupAt.value(), groupAt.ignoreCase());
                }
                GroupMessage groupAll = AnnotatedElementUtils.findMergedAnnotation(method, GroupMessage.class);
                if (groupAll != null) {
                    register(result, bean, method, MessageEventType.GROUP_MESSAGE, groupAll.value(), groupAll.ignoreCase());
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

    /**
     * 注册 {@link QQBotMessage} 标注的方法：一个方法可以同时注册到多个消息场景。
     * 重复声明的场景会被去重，注册顺序按枚举声明顺序（群@、群全量、单聊）。
     */
    private void registerUnified(Map<MessageEventType, List<ListenerDefinition>> result,
                                 Object bean,
                                 Method method,
                                 QQBotMessage message) {
        EnumSet<MessageEventType> types = EnumSet.noneOf(MessageEventType.class);
        Collections.addAll(types, message.type());
        if (types.isEmpty()) {
            log.warn("监听方法 {} 的 @QQBotMessage 未指定任何消息场景，已忽略", userClassName(bean) + "#" + method.getName());
            return;
        }
        for (MessageEventType type : types) {
            register(result, bean, method, type, message.value(), message.ignoreCase());
        }
    }

    private void register(Map<MessageEventType, List<ListenerDefinition>> result,
                          Object bean,
                          Method method,
                          MessageEventType type,
                          String command,
                          boolean ignoreCase) {
        if (!isSupported(method, type)) {
            return;
        }
        // 无参方法、返回值类型不做限制；返回 String 时会被当作回复内容自动发送
        ReflectionUtils.makeAccessible(method);
        result.get(type).add(new ListenerDefinition(bean, method, type, command == null ? "" : command.trim(), ignoreCase));
    }

    /**
     * 参数类型支持：消息体、BotEvent、QQBotMessageSender、String。
     * 消息体已合并为 {@link MessagePayload}，三类场景通用，不再按场景区分类型。
     */
    private boolean isSupported(Method method, MessageEventType type) {
        for (Class<?> paramType : method.getParameterTypes()) {
            if (paramType.isPrimitive()) {
                return false;
            }
            boolean supported = paramType.isAssignableFrom(MessagePayload.class)
                    || BotEvent.class.isAssignableFrom(paramType)
                    || paramType.isAssignableFrom(QQBotMessageSender.class)
                    || String.class.equals(paramType);
            if (!supported) {
                log.warn("监听方法 {}#{} 的参数类型 {} 不受支持，已跳过该场景；可用参数类型：{}、BotEvent、QQBotMessageSender、String",
                        method.getDeclaringClass().getSimpleName(), method.getName(),
                        paramType.getSimpleName(), MessagePayload.class.getSimpleName());
                return false;
            }
        }
        return true;
    }

    private String userClassName(Object bean) {
        return ClassUtils.getUserClass(bean).getSimpleName();
    }
}
