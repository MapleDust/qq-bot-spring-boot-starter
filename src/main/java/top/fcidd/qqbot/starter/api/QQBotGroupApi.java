package top.fcidd.qqbot.starter.api;

import lombok.extern.log4j.Log4j2;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import top.fcidd.qqbot.starter.autoconfigure.AccessTokenProvider;
import top.fcidd.qqbot.starter.autoconfigure.QQBotProperties;
import top.fcidd.qqbot.starter.util.JsonUtils;

import java.time.Duration;

/**
 * 群信息相关接口封装。
 * <p>
 * 目前提供「获取机器人群内状态」，用于确认群主是否已给机器人开启
 * 「获取群内全部消息」（这是接收 GROUP_MESSAGE_CREATE 全量消息事件的前置条件）。
 */
@Log4j2
public class QQBotGroupApi {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final AccessTokenProvider tokenProvider;
    private final QQBotProperties props;
    private final WebClient webClient;

    public QQBotGroupApi(AccessTokenProvider tokenProvider, QQBotProperties props) {
        this.tokenProvider = tokenProvider;
        this.props = props;
        this.webClient = WebClient.builder().baseUrl("https://api.sgroup.qq.com").build();
    }

    /**
     * 获取机器人在群内的状态（响应式）。
     *
     * @param groupOpenid 群 OpenID，可从群消息事件的 group_openid 字段获取
     */
    public Mono<BotState> getBotStateAsync(String groupOpenid) {
        if (groupOpenid == null || groupOpenid.isBlank()) {
            return Mono.empty();
        }
        return tokenProvider.getToken()
                .flatMap(token -> webClient.get()
                        .uri("/v2/groups/{groupOpenid}/bot_state", groupOpenid)
                        .header("Authorization", "QQBot " + token)
                        .header("X-Union-Appid", props.getAppId())
                        .exchangeToMono(response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(raw -> {
                                    if (!response.statusCode().is2xxSuccessful()) {
                                        log.warn("查询机器人群内状态失败，http={}，响应={}",
                                                response.statusCode().value(), raw);
                                        return null;
                                    }
                                    try {
                                        return JsonUtils.fromJson(raw, BotState.class);
                                    } catch (Exception ex) {
                                        log.warn("解析机器人群内状态失败，响应={}", raw, ex);
                                        return null;
                                    }
                                }))
                        .timeout(TIMEOUT))
                .onErrorResume(ex -> {
                    log.warn("查询机器人群内状态异常：{}", ex.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 获取机器人在群内的状态（阻塞调用），失败返回 null。
     */
    public BotState getBotState(String groupOpenid) {
        try {
            return getBotStateAsync(groupOpenid).block(TIMEOUT.plusSeconds(2));
        } catch (Exception ex) {
            log.warn("查询机器人群内状态异常：{}", ex.getMessage());
            return null;
        }
    }

    /**
     * 是否已开启「获取群内全部消息」。
     * 为 false 时平台不会推送 GROUP_MESSAGE_CREATE，需群主在手机 QQ 中调整设置。
     */
    public boolean isReceiveAllMessages(String groupOpenid) {
        BotState state = getBotState(groupOpenid);
        return state != null && state.receiveAllMessages();
    }
}
