package top.fcidd.qqbot.starter.model.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 群聊消息事件体，对应 GROUP_AT_MESSAGE_CREATE（群内 @机器人）与
 * GROUP_MESSAGE_CREATE（群聊全量模式，二者字段完全一致）。
 * 文档：https://bot.q.qq.com/wiki/develop/api-v2/autogen/event/group_at_message_create.html
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GroupMessagePayload {

    /** 消息 ID，可用于被动回复和撤回 */
    @JsonProperty("id")
    private String id;

    /** 发送者（member_openid 有值） */
    @JsonProperty("author")
    private MessageUser author;

    /** 消息文本内容（群内 @机器人 时已去除 @机器人 的前缀） */
    @JsonProperty("content")
    private String content;

    /** 群 OpenID */
    @JsonProperty("group_openid")
    private String groupOpenid;

    /** 消息发送时间，RFC3339 格式 */
    @JsonProperty("timestamp")
    private String timestamp;

    /** 消息内容类型：0=普通文本, 3=结构化卡片, 101=并行消息, 102=聊天记录, 103=引用消息 */
    @JsonProperty("message_type")
    private Integer messageType;

    /** 消息场景上下文 */
    @JsonProperty("message_scene")
    private MessageScene messageScene;

    /** 消息附件（图片、文件、语音等） */
    @JsonProperty("attachments")
    private List<MessageAttachment> attachments;

    /** 消息中 @ 的用户列表（不含 @机器人 自身） */
    @JsonProperty("mentions")
    private List<MessageUser> mentions;

    /** 结构化卡片消息数据 */
    @JsonProperty("ark_data")
    private ArkData arkData;

    /** 消息元素列表 */
    @JsonProperty("msg_elements")
    private List<MsgElement> msgElements;

    /**
     * 这条消息是否 @ 了当前机器人。
     * <p>
     * 全量模式（GROUP_MESSAGE_CREATE）下，群内每条消息都会推送，@机器人 的消息也在其中，
     * 平台通过 {@code mentions[].is_you=true} 标记“就是 @ 的当前 bot”，据此把 @消息从全量消息里区分出来。
     */
    public boolean mentionsBot() {
        if (mentions == null || mentions.isEmpty()) {
            return false;
        }
        for (MessageUser mention : mentions) {
            if (mention == null) {
                continue;
            }
            if (Boolean.TRUE.equals(mention.getIsYou())) {
                return true;
            }
            // 兜底：个别推送不带 is_you，但被 @ 的对象是机器人且是单个 @
            if (Boolean.TRUE.equals(mention.getBot()) && !"all".equals(mention.getScope())) {
                return true;
            }
        }
        return false;
    }
}
