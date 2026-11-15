package top.fcidd.qqbot.starter.autoconfigure;

import lombok.extern.log4j.Log4j2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.fcidd.qqbot.starter.event.EventHandler;

@Log4j2
@Configuration
@EnableConfigurationProperties(QQBotProperties.class)
@ConditionalOnProperty(prefix = "qq.bot", name = "app-id", matchIfMissing = false)
public class QQBotAutoConfiguration {

    @Bean
    public AccessTokenProvider accessTokenProvider(QQBotProperties props) {
        return new AccessTokenProvider(props);
    }

    @Bean
    public EventHandler eventHandler(ApplicationEventPublisher publisher) {
        return new EventHandler(publisher);
    }

    @Bean
    public QQBotWebSocketClient qqBotWebSocketClient(QQBotProperties props,
                                                     EventHandler handler,
                                                     AccessTokenProvider tokenProvider) {
        return new QQBotWebSocketClient(props, handler, tokenProvider);
    }

    @Bean
    public QQBotStarterBootstrap qqBotStarterBootstrap(QQBotWebSocketClient client, QQBotProperties props) {
        return new QQBotStarterBootstrap(client, props);
    }
}
