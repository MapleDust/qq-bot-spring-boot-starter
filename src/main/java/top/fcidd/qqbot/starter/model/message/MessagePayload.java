package top.fcidd.qqbot.starter.model.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 统一的消息事件体，同时覆盖三类消息场景（字段超集）：
 * <ul>
 *     <li>GROUP_AT_MESSAGE_CREATE — 群聊 @机器人</li>
 *     <li>GROUP_MESSAGE_CREATE — 群聊全量模式下的每条消息</li>
 *     <li>C2C_MESSAGE_CREATE — 单聊</li>
 * </ul>
 * 群聊事件带 {@code group_openid}，单聊事件不带（改由 {@code author.user_openid} 标识会话），
 * 因此 {@link #isGroup()} / {@link #isC2C()} 可以自动判断当前是群聊还是单聊，业务侧不需要声明两个类型。
 * <p>
 * 文档：https://bot.q.qq.com/wiki/develop/api-v2/autogen/event/group_at_message_create.html
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessagePayload {

    /** 消息 ID，可用于被动回复和撤回 */
    @JsonProperty("id")
    private String id;

    /** 发送者：群聊场景有 member_openid，单聊场景有 user_openid */
    @JsonProperty("author")
    private MessageUser author;

    /** 消息文本内容（群内 @机器人 时已去除 @机器人 前缀） */
    @JsonProperty("content")
    private String content;

    /** 群 OpenID，仅群聊事件有值 */
    @JsonProperty("group_openid")
    private String groupOpenid;

    /** 跨应用关联群 OpenID（绑定了第三方应用时有效） */
    @JsonProperty("union_group_openid")
    private String unionGroupOpenid;

    /** 消息发送时间，RFC3339 格式 */
    @JsonProperty("timestamp")
    private String timestamp;

    /** 消息内容类型：0=普通文本, 2=Markdown, 3=结构化卡片, 7=富媒体, 101=并行消息, 102=聊天记录, 103=引用消息 */
    @JsonProperty("message_type")
    private Integer messageType;

    /** 消息场景上下文（含消息索引、鉴权令牌等） */
    @JsonProperty("message_scene")
    private MessageScene messageScene;

    /** 消息附件（图片、文件、语音等） */
    @JsonProperty("attachments")
    private List<MessageAttachment> attachments;

    /** 消息中 @ 的用户列表（含 is_you / scope 标记） */
    @JsonProperty("mentions")
    private List<MessageUser> mentions;

    /** 结构化卡片消息数据（message_type=3 时有值） */
    @JsonProperty("ark_data")
    private ArkData arkData;

    /** 消息元素列表（message_type=103 引用消息时包含被引用内容） */
    @JsonProperty("msg_elements")
    private List<MsgElement> msgElements;

    /** 是否群聊消息（群聊事件一定带 group_openid，单聊事件没有该字段） */
    public boolean isGroup() {
        return groupOpenid != null && !groupOpenid.isBlank();
    }

    /** 是否单聊消息 */
    public boolean isC2C() {
        return !isGroup();
    }

    /** 场景中文名，便于日志/业务分支 */
    public String sceneLabel() {
        return isGroup() ? "群聊" : "单聊";
    }

    /**
     * 本条消息是否 @ 了当前机器人。
     * <p>
     * 全量模式（GROUP_MESSAGE_CREATE）下群内每条消息都会推送，@机器人 的消息也在其中，
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

    /** 被动回复的目标 OpenID：群聊返回 group_openid，单聊返回 author.user_openid */
    public String replyTargetOpenid() {
        if (isGroup()) {
            return groupOpenid;
        }
        if (author == null) {
            return null;
        }
        return author.getUserOpenid() != null && !author.getUserOpenid().isBlank()
                ? author.getUserOpenid()
                : author.getId();
    }

    /** 发送者在本场景下的 OpenID：群聊 member_openid，单聊 user_openid */
    public String senderOpenid() {
        if (author == null) {
            return null;
        }
        return isGroup() ? author.getMemberOpenid() : author.getUserOpenid();
    }
}
