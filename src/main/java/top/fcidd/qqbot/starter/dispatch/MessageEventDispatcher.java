package top.fcidd.qqbot.starter.dispatch;

import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import top.fcidd.qqbot.starter.event.BotEvent;
import top.fcidd.qqbot.starter.event.MessageEventLogger;
import top.fcidd.qqbot.starter.model.message.C2CMessagePayload;
import top.fcidd.qqbot.starter.model.message.GroupMessagePayload;
import top.fcidd.qqbot.starter.sender.QQBotMessageSender;
import top.fcidd.qqbot.starter.util.JsonUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 把网关消息事件分发给注解声明的处理方法。
 * <p>
 * 处理方法的调用被投递到弹性线程池，避免阻塞 Netty 事件循环；
 * 方法返回非空 {@link String} 时，会自动作为被动回复发送给消息来源。
 */
@Slf4j
public class MessageEventDispatcher {

    /** 处理器执行线程池：可安全执行阻塞式发送消息 */
    private static final Scheduler SCHEDULER = Schedulers.boundedElastic();

    private final MessageHandlerRegistry registry;
    private final QQBotMessageSender sender;

    public MessageEventDispatcher(MessageHandlerRegistry registry, QQBotMessageSender sender) {
        this.registry = registry;
        this.sender = sender;
    }

    /**
     * 分发一条网关事件，不是消息事件时直接忽略。
     */
    public void dispatch(BotEvent event) {
        if (event == null || event.getOp() == null || event.getOp() != 0
                || event.getT() == null || event.getD() == null) {
            return;
        }
        switch (event.getT()) {
            case MessageEventLogger.GROUP_AT_MESSAGE_CREATE ->
                    dispatchGroupEvent(event, MessageEventType.GROUP_AT);
            case MessageEventLogger.GROUP_MESSAGE_CREATE ->
                    dispatchGroupEvent(event, MessageEventType.GROUP_MESSAGE);
            case MessageEventLogger.C2C_MESSAGE_CREATE -> dispatchC2CEvent(event);
            default -> {
            }
        }
    }

    private void dispatchGroupEvent(BotEvent event, MessageEventType type) {
        GroupMessagePayload payload;
        try {
            payload = JsonUtils.convert(event.getD(), GroupMessagePayload.class);
        } catch (IllegalArgumentException ex) {
            log.warn("解析群消息事件失败，事件类型={}", event.getT(), ex);
            return;
        }
        if (payload == null) {
            return;
        }
        List<ListenerDefinition> definitions = registry.handlers(type);
        if (definitions.isEmpty()) {
            return;
        }
        String content = payload.getContent();
        for (ListenerDefinition definition : definitions) {
            if (!definition.matches(content)) {
                continue;
            }
            submit(definition, payload, content, event);
        }
    }

    private void dispatchC2CEvent(BotEvent event) {
        C2CMessagePayload payload;
        try {
            payload = JsonUtils.convert(event.getD(), C2CMessagePayload.class);
        } catch (IllegalArgumentException ex) {
            log.warn("解析单聊消息事件失败", ex);
            return;
        }
        if (payload == null) {
            return;
        }
        List<ListenerDefinition> definitions = registry.handlers(MessageEventType.C2C);
        if (definitions.isEmpty()) {
            return;
        }
        String content = payload.getContent();
        for (ListenerDefinition definition : definitions) {
            if (!definition.matches(content)) {
                continue;
            }
            submit(definition, payload, content, event);
        }
    }

    private void submit(ListenerDefinition definition, Object payload, String content, BotEvent event) {
        log.debug("消息事件命中处理器 {}，指令={}，内容={}",
                definition.describe(), definition.command(), content);
        SCHEDULER.schedule(() -> invoke(definition, payload, content, event));
    }

    private void invoke(ListenerDefinition definition, Object payload, String content, BotEvent event) {
        try {
            Object[] args = buildArgs(definition, payload, content, event);
            Object result = definition.method().invoke(definition.bean(), args);
            if (result instanceof String reply && !reply.isBlank()) {
                sendReply(payload, reply);
            }
        } catch (InvocationTargetException ex) {
            log.error("消息处理器 {} 执行异常", definition.describe(), ex.getTargetException());
        } catch (Exception ex) {
            log.error("消息处理器 {} 调用失败", definition.describe(), ex);
        }
    }

    private Object[] buildArgs(ListenerDefinition definition, Object payload, String content, BotEvent event) {
        Method method = definition.method();
        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] args = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            if (parameterType.isInstance(payload)) {
                // GroupMessagePayload / C2CMessagePayload / Object
                args[i] = payload;
            } else if (BotEvent.class.isAssignableFrom(parameterType)) {
                args[i] = event;
            } else if (QQBotMessageSender.class.isAssignableFrom(parameterType)) {
                args[i] = sender;
            } else if (String.class.equals(parameterType)) {
                // 指令之后的参数，未配置指令时为整条消息内容
                args[i] = definition.extractArgs(content);
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private void sendReply(Object payload, String reply) {
        if (sender == null) {
            log.warn("未配置 QQBotMessageSender，跳过自动回复：{}", reply);
            return;
        }
        if (payload instanceof GroupMessagePayload groupMessage) {
            sender.replyGroup(groupMessage, reply);
        } else if (payload instanceof C2CMessagePayload c2cMessage) {
            sender.replyC2C(c2cMessage, reply);
        }
    }
}
