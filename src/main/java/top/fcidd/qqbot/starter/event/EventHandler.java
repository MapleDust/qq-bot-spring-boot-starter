package top.fcidd.qqbot.starter.event;

import lombok.extern.log4j.Log4j2;
import org.springframework.context.ApplicationEventPublisher;
import top.fcidd.qqbot.starter.dispatch.MessageEventDispatcher;

@Log4j2
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
        if (event == null) {
            return;
        }
        // Dispatch 事件用 info；Hello / 心跳应答（op=10 / 11）等控制帧降到 debug，
        // 否则每 41 秒一条 info 会把日志刷屏
        if (event.getOp() != null && event.getOp() == 0) {
            log.info("收到网关事件 op=0, t={}, s={}", event.getT(), event.getS());
        } else {
            log.debug("收到网关事件 op={}, t={}, s={}", event.getOp(), event.getT(), event.getS());
        }
        // 日志/分发/事件发布任一环节出错都不应影响网关继续收消息
        try {
            if (messageEventLogger != null) {
                messageEventLogger.handle(event);
            }
        } catch (Exception ex) {
            log.error("消息事件日志打印失败，t={}", event.getT(), ex);
        }
        try {
            if (messageEventDispatcher != null) {
                messageEventDispatcher.dispatch(event);
            }
        } catch (Exception ex) {
            log.error("消息事件分发失败，t={}", event.getT(), ex);
        }
        try {
            publisher.publishEvent(event);
        } catch (Exception ex) {
            log.error("发布 BotEvent 失败，t={}", event.getT(), ex);
        }
    }
}
