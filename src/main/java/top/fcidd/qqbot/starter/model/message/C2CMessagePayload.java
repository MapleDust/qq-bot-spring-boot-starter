package top.fcidd.qqbot.starter.model.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 单聊消息事件体，对应 C2C_MESSAGE_CREATE。
 * 文档：https://bot.q.qq.com/wiki/develop/api-v2/autogen/event/c2c_message_create.html
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class C2CMessagePayload {

    /** 消息 ID，可用于被动回复和撤回 */
    @JsonProperty("id")
    private String id;

    /** 发送者（user_openid 有值） */
    @JsonProperty("author")
    private MessageUser author;

    /** 消息文本内容 */
    @JsonProperty("content")
    private String content;

    /** 消息发送时间，RFC3339 格式 */
    @JsonProperty("timestamp")
    private String timestamp;

    /** 消息内容类型：0=普通文本, 3=结构化卡片, 101=并行消息, 102=聊天记录, 103=引用消息 */
    @JsonProperty("message_type")
    private Integer messageType;

    /** 消息场景上下文（含消息索引、鉴权令牌等） */
    @JsonProperty("message_scene")
    private MessageScene messageScene;

    /** 消息附件（图片、文件、语音等） */
    @JsonProperty("attachments")
    private List<MessageAttachment> attachments;

    /** 结构化卡片消息数据（message_type=3 时有值） */
    @JsonProperty("ark_data")
    private ArkData arkData;

    /** 消息元素列表（message_type=103 引用消息时包含被引用内容） */
    @JsonProperty("msg_elements")
    private List<MsgElement> msgElements;
}
