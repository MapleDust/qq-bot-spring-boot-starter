package top.fcidd.qqbot.starter.autoconfigure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.fcidd.qqbot.starter.dispatch.MessageEventDispatcher;
import top.fcidd.qqbot.starter.dispatch.MessageHandlerRegistry;
import top.fcidd.qqbot.starter.event.EventHandler;
import top.fcidd.qqbot.starter.event.MessageEventLogger;
import top.fcidd.qqbot.starter.api.QQBotGroupApi;
import top.fcidd.qqbot.starter.sender.QQBotMessageSender;

/**
 * QQ 机器人自动配置。
 * <p>
 * 提供网关连接、消息日志、消息发送封装、注解监听器分发等能力。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(QQBotProperties.class)
@ConditionalOnProperty(prefix = "qq.bot", name = "app-id", matchIfMissing = false)
public class QQBotAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AccessTokenProvider accessTokenProvider(QQBotProperties props) {
        return new AccessTokenProvider(props);
    }

    /**
     * 群聊@机器人 / 单聊 / 群聊全量消息的日志处理器，可自定义 Bean 覆盖。
     */
    @Bean
    @ConditionalOnMissingBean
    public MessageEventLogger messageEventLogger() {
        return new MessageEventLogger();
    }

    /**
     * 消息发送封装：群聊、单聊消息发送与被动回复。
     */
    @Bean
    @ConditionalOnMissingBean
    public QQBotMessageSender qqBotMessageSender(AccessTokenProvider accessTokenProvider, QQBotProperties props) {
        return new QQBotMessageSender(accessTokenProvider, props);
    }

    /**
     * 群信息接口封装：查询机器人在群内的状态（群全量消息是否已开启）。
     */
    @Bean
    @ConditionalOnMissingBean
    public QQBotGroupApi qqBotGroupApi(AccessTokenProvider accessTokenProvider, QQBotProperties props) {
        return new QQBotGroupApi(accessTokenProvider, props);
    }

    /**
     * 注解监听器扫描与注册表。
     */
    @Bean
    @ConditionalOnMissingBean
    public MessageHandlerRegistry messageHandlerRegistry(ApplicationContext applicationContext) {
        return new MessageHandlerRegistry(applicationContext);
    }

    /**
     * 消息事件分发器，把事件路由到 @QQBotListener 中声明的方法。
     */
    @Bean
    @ConditionalOnMissingBean
    public MessageEventDispatcher messageEventDispatcher(MessageHandlerRegistry registry,
                                                         QQBotMessageSender sender) {
        return new MessageEventDispatcher(registry, sender);
    }

    @Bean
    public EventHandler eventHandler(ApplicationEventPublisher publisher,
                                     MessageEventLogger messageEventLogger,
                                     MessageEventDispatcher messageEventDispatcher) {
        return new EventHandler(publisher, messageEventLogger, messageEventDispatcher);
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
