package top.fcidd.qqbot.starter.autoconfigure;

import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
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

    /**
     * 容器关闭时主动断开连接并停掉调度线程，避免应用退出后残留连接/线程。
     */
    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        try {
            webSocketClient.stop();
            log.info("应用关闭，QQ 机器人 Gateway 已停止");
        } catch (Exception ex) {
            log.warn("停止 QQ 机器人 Gateway 时异常：{}", ex.getMessage());
        }
    }
}
