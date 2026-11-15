package top.fcidd.qqbot.starter.autoconfigure;

import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@Log4j2
public class QQBotStarterBootstrap {
    private final QQBotWebSocketClient webSocketClient;
    private final QQBotProperties properties;

    public QQBotStarterBootstrap(QQBotWebSocketClient webSocketClient, QQBotProperties properties) {
        this.webSocketClient = webSocketClient;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!properties.isAutoStart()) {
            log.info("qq.bot.auto-start = false，跳过自动启动");
            return;
        }
        log.info("应用就绪，自动启动 QQ 机器人 Gateway");
        webSocketClient.start();
    }
}
