package top.fcidd.qqbot.starter.event;

import lombok.extern.log4j.Log4j2;
import org.springframework.context.ApplicationEventPublisher;

@Log4j2
public class EventHandler {
    private final ApplicationEventPublisher publisher;

    public EventHandler(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void onRawEvent(BotEvent event) {
        log.info("收到网关事件 op={}, t={}, s={}", event.getOp(), event.getT(), event.getS());
        publisher.publishEvent(event);
    }
}
