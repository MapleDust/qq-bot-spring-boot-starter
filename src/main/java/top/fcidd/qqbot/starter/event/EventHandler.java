package top.fcidd.qqbot.starter.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import top.fcidd.qqbot.starter.dispatch.MessageEventDispatcher;

@Slf4j
public class EventHandler {
    private final ApplicationEventPublisher publisher;
    private final MessageEventLogger messageEventLogger;
    private final MessageEventDispatcher messageEventDispatcher;

    public EventHandler(ApplicationEventPublisher publisher) {
        this(publisher, new MessageEventLogger(), null);
    }

    public EventHandler(ApplicationEventPublisher publisher, MessageEventLogger messageEventLogger) {
        this(publisher, messageEventLogger, null);
    }

    public EventHandler(ApplicationEventPublisher publisher,
                        MessageEventLogger messageEventLogger,
                        MessageEventDispatcher messageEventDispatcher) {
        this.publisher = publisher;
        this.messageEventLogger = messageEventLogger;
        this.messageEventDispatcher = messageEventDispatcher;
    }

    public void onRawEvent(BotEvent event) {
        log.info("收到网关事件 op={}, t={}, s={}", event.getOp(), event.getT(), event.getS());
        // 群聊@机器人 / 单聊 / 群聊全量消息的详细日志
        if (messageEventLogger != null) {
            messageEventLogger.handle(event);
        }
        // 分发给 @QQBotListener 中声明的方法
        if (messageEventDispatcher != null) {
            messageEventDispatcher.dispatch(event);
        }
        publisher.publishEvent(event);
    }
}
