package top.fcidd.qqbot.starter.dispatch;

import lombok.extern.log4j.Log4j2;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把网关消息事件分发给注解声明的处理方法。
 * <p>
 * 处理方法的调用被投递到弹性线程池，避免阻塞 Netty 事件循环；
 * 方法返回非空 {@link String} 时，会自动作为被动回复发送给消息来源。
 */
@Log4j2
public class MessageEventDispatcher {

    /** 处理器执行线程池：可安全执行阻塞式发送消息 */
    private static final Scheduler SCHEDULER = Schedulers.boundedElastic();

    private final MessageHandlerRegistry registry;
    private final QQBotMessageSender sender;
    /** 已分发过的 “消息ID|场景”，用于丢弃同一消息的重复推送（全量模式下同一条 @消息可能被推送两次） */
    private final Map<String, Boolean> dispatchedMemo = new LinkedHashMap<>();
    private static final int MAX_DISPATCH_MEMO = 600;

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
                    dispatchGroupEvent(event, true);
            case MessageEventLogger.GROUP_MESSAGE_CREATE -> dispatchGroupEvent(event, false);
            case MessageEventLogger.C2C_MESSAGE_CREATE -> dispatchC2CEvent(event);
            default -> {
            }
        }
    }

    private void dispatchGroupEvent(BotEvent event, boolean fromAtEvent) {
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
        // 全量模式（GROUP_MESSAGE_CREATE）下 @机器人 的消息也在全量事件里推送，
        // 必须按 mentions[].is_you 判断场景，否则 @消息只会走全量处理器、@处理器不触发（表现为“不回复”）
        boolean atBot = fromAtEvent || payload.mentionsBot();
        MessageEventType type = atBot ? MessageEventType.GROUP_AT : MessageEventType.GROUP_MESSAGE;
        if (!markDispatched(payload.getId(), type)) {
            log.debug("同一条消息的 {} 场景已分发过，忽略重复推送，消息ID={}", type, payload.getId());
            return;
        }
        List<ListenerDefinition> definitions = registry.handlers(type);
        if (definitions.isEmpty()) {
            return;
        }
        String content = atBot ? stripLeadingMention(payload.getContent()) : payload.getContent();
        for (ListenerDefinition definition : definitions) {
            if (!definition.matches(content)) {
                continue;
            }
            submit(definition, payload, content, event);
        }
    }

    /**
     * 去掉消息开头可能存在的 “&lt;@xxx&gt;” @机器人标记，保证指令能匹配上。
     * 只在已确认本条消息 @ 了本机器人时调用，避免误伤 @ 其他成员的普通消息。
     */
    private static String stripLeadingMention(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.stripLeading();
        if (!trimmed.startsWith("<@")) {
            return content;
        }
        int end = trimmed.indexOf('>');
        if (end < 0 || end + 1 >= trimmed.length()) {
            return content;
        }
        return trimmed.substring(end + 1).stripLeading();
    }

    /**
     * 记录并判断 “消息ID + 场景” 是否已分发。返回 false 表示重复，应当跳过。
     */
    private synchronized boolean markDispatched(String messageId, MessageEventType type) {
        if (messageId == null || messageId.isBlank()) {
            return true;
        }
        String key = messageId + "|" + type;
        if (dispatchedMemo.containsKey(key)) {
            return false;
        }
        if (dispatchedMemo.size() >= MAX_DISPATCH_MEMO) {
            Iterator<String> eldest = dispatchedMemo.keySet().iterator();
            if (eldest.hasNext()) {
                eldest.next();
                eldest.remove();
            }
        }
        dispatchedMemo.put(key, Boolean.TRUE);
        return true;
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
