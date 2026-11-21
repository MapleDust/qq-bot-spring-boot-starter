package top.fcidd.qqbot.starter.sender;

import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import top.fcidd.qqbot.starter.autoconfigure.AccessTokenProvider;
import top.fcidd.qqbot.starter.autoconfigure.QQBotProperties;
import top.fcidd.qqbot.starter.model.message.MessagePayload;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QQ 机器人消息发送封装。
 * <p>
 * 提供群聊、单聊消息的发送方法：
 * <ul>
 *     <li>带 msg_id 的是<b>被动回复</b>（有效期内可回复，群聊 5 分钟内最多 5 次、单聊 60 分钟内最多 4 次）</li>
 *     <li>不带 msg_id 的是<b>主动消息</b>（受平台主动消息频控，用户可关闭接收）</li>
 * </ul>
 * 同步方法内部会阻塞等待响应，请在业务线程（监听器已被派发到弹性线程池）或非 IO 线程中调用；
 * 响应式场景使用对应的 xxxAsync 方法。
 */
@Log4j2
public class QQBotMessageSender {

    private static final String BASE_URL = "https://api.sgroup.qq.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** 被动回复的 msg_seq 需要按 msg_id 递增；用有序表控制容量，避免长期运行内存无限增长 */
    private static final int MAX_TRACKED_MSG = 2000;

    private final AccessTokenProvider tokenProvider;
    private final QQBotProperties props;
    private final WebClient webClient;
    /** 按插入顺序淘汰最旧的 msg_id，不能整体 clear——那会把正在回复的 msg_id 的 seq 归零，重复 msg_seq 会被平台去重丢弃 */
    private final Map<String, AtomicInteger> replySeq = new LinkedHashMap<>();

    public QQBotMessageSender(AccessTokenProvider tokenProvider, QQBotProperties props) {
        this.tokenProvider = tokenProvider;
        this.props = props;
        this.webClient = WebClient.builder().baseUrl(BASE_URL).build();
    }

    // ------------------------------------------------------------------ 群聊

    /** 发送群聊消息（主动消息），返回响应式结果 */
    public Mono<SendResult> sendGroupMessageAsync(String groupOpenid, String content) {
        return sendGroupMessageAsync(groupOpenid, content, null, 0);
    }

    /**
     * 发送群聊消息，返回响应式结果。
     *
     * @param replyMessageId 被动回复时传入收到的消息 ID，主动消息传 null
     * @param msgSeq         被动回复序号，同一 msg_id 多次回复需递增
     */
    public Mono<SendResult> sendGroupMessageAsync(String groupOpenid, String content,
                                                  String replyMessageId, int msgSeq) {
        return sendAsync("/v2/groups/" + groupOpenid + "/messages", content, replyMessageId, msgSeq);
    }

    /** 发送群聊消息（主动消息），阻塞等待结果 */
    public SendResult sendGroupMessage(String groupOpenid, String content) {
        return sendGroupMessage(groupOpenid, content, null, 0);
    }

    /** 发送群聊消息，阻塞等待结果 */
    public SendResult sendGroupMessage(String groupOpenid, String content, String replyMessageId, int msgSeq) {
        return block(sendGroupMessageAsync(groupOpenid, content, replyMessageId, msgSeq), "群聊");
    }

    /**
     * 回复一条消息（自动判断群聊 / 单聊），自动带上 msg_id 与自增 msg_seq。
     * <p>
     * 群聊走 {@code /v2/groups/{group_openid}/messages}，单聊走 {@code /v2/users/{user_openid}/messages}，
     * 因此业务侧不需要再区分消息来自哪个场景。
     */
    public SendResult reply(MessagePayload message, String content) {
        String invalid = validateReply(message);
        if (invalid != null) {
            return SendResult.failure(invalid);
        }
        if (message.isGroup()) {
            return sendGroupMessage(message.getGroupOpenid(), content, message.getId(), nextSeq(message.getId()));
        }
        return sendC2CMessage(message.replyTargetOpenid(), content, message.getId(), nextSeq(message.getId()));
    }

    /** 回复一条消息（响应式版本） */
    public Mono<SendResult> replyAsync(MessagePayload message, String content) {
        String invalid = validateReply(message);
        if (invalid != null) {
            return Mono.just(SendResult.failure(invalid));
        }
        if (message.isGroup()) {
            return sendGroupMessageAsync(message.getGroupOpenid(), content, message.getId(), nextSeq(message.getId()));
        }
        return sendC2CMessageAsync(message.replyTargetOpenid(), content, message.getId(), nextSeq(message.getId()));
    }

    /** 校验能否被动回复，返回失败原因；可以回复时返回 null */
    private String validateReply(MessagePayload message) {
        if (message == null) {
            return "消息为空，无法被动回复";
        }
        if (message.getId() == null || message.getId().isBlank()) {
            return "消息缺少 msg_id，无法被动回复";
        }
        if (message.replyTargetOpenid() == null || message.replyTargetOpenid().isBlank()) {
            return message.isGroup()
                    ? "群消息缺少 group_openid，无法被动回复"
                    : "单聊消息缺少 user_openid，无法被动回复";
        }
        return null;
    }

    // ------------------------------------------------------------------ 单聊

    /** 发送单聊消息（主动消息），返回响应式结果 */
    public Mono<SendResult> sendC2CMessageAsync(String userOpenid, String content) {
        return sendC2CMessageAsync(userOpenid, content, null, 0);
    }

    /** 发送单聊消息，返回响应式结果 */
    public Mono<SendResult> sendC2CMessageAsync(String userOpenid, String content,
                                                String replyMessageId, int msgSeq) {
        return sendAsync("/v2/users/" + userOpenid + "/messages", content, replyMessageId, msgSeq);
    }

    /** 发送单聊消息（主动消息），阻塞等待结果 */
    public SendResult sendC2CMessage(String userOpenid, String content) {
        return sendC2CMessage(userOpenid, content, null, 0);
    }

    /** 发送单聊消息，阻塞等待结果 */
    public SendResult sendC2CMessage(String userOpenid, String content, String replyMessageId, int msgSeq) {
        return block(sendC2CMessageAsync(userOpenid, content, replyMessageId, msgSeq), "单聊");
    }

    // ------------------------------------------------------------------ 内部实现

    private Mono<SendResult> sendAsync(String path, String content, String replyMessageId, int msgSeq) {
        if (content == null || content.isBlank()) {
            return Mono.just(SendResult.failure("消息内容为空，已跳过发送"));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("content", content);
        body.put("msg_type", 0);
        if (replyMessageId != null && !replyMessageId.isBlank()) {
            body.put("msg_id", replyMessageId);
            body.put("msg_seq", msgSeq);
        }

        return tokenProvider.getToken()
                .flatMap(token -> webClient.post()
                        .uri(path)
                        .header("Authorization", "QQBot " + token)
                        .header("X-Union-Appid", props.getAppId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .exchangeToMono(response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(raw -> SendResult.from(response.statusCode().value(), raw)))
                        .timeout(TIMEOUT)
                        .doOnNext(result -> {
                            if (result.success()) {
                                log.info("消息发送成功，path={}，消息ID={}", path, result.messageId());
                            } else {
                                log.warn("消息发送失败，path={}，{}", path, result.describe());
                            }
                        }))
                .onErrorResume(ex -> Mono.just(SendResult.failure("发送请求异常：" + ex.getMessage())))
                .switchIfEmpty(Mono.fromSupplier(() -> SendResult.failure("获取 access_token 失败")));
    }

    private SendResult block(Mono<SendResult> mono, String scene) {
        try {
            SendResult result = mono.block(TIMEOUT.plusSeconds(2));
            return result == null ? SendResult.failure(scene + "消息发送超时") : result;
        } catch (Exception ex) {
            log.warn("{}消息发送异常", scene, ex);
            return SendResult.failure(scene + "消息发送异常：" + ex.getMessage());
        }
    }

    private synchronized int nextSeq(String messageId) {
        AtomicInteger seq = replySeq.get(messageId);
        if (seq == null) {
            if (replySeq.size() >= MAX_TRACKED_MSG) {
                Iterator<String> eldest = replySeq.keySet().iterator();
                if (eldest.hasNext()) {
                    eldest.next();
                    eldest.remove();
                }
            }
            seq = new AtomicInteger();
            replySeq.put(messageId, seq);
        }
        return seq.incrementAndGet();
    }
}
