package top.fcidd.qqbot.starter.model.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 消息元素，用于引用消息、合并聊天记录等复合消息（message_type=103/102）。
 * msg_elements 为递归结构。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MsgElement {

    /** 消息元素在列表中的引用消息索引 */
    @JsonProperty("msg_idx")
    private String msgIdx;

    /** 该元素对应的消息发送者 */
    @JsonProperty("author")
    private MessageUser author;

    /** 0=普通文本, 3=结构化卡片, 101=并行消息, 102=聊天记录, 103=引用消息 */
    @JsonProperty("message_type")
    private Integer messageType;

    /** 消息正文内容 */
    @JsonProperty("content")
    private String content;

    /** 该元素携带的附件 */
    @JsonProperty("attachments")
    private List<MessageAttachment> attachments;

    /** 结构化卡片消息数据（message_type=3 时有值） */
    @JsonProperty("ark_data")
    private ArkData arkData;

    /** 嵌套消息元素列表（递归结构） */
    @JsonProperty("msg_elements")
    private List<MsgElement> msgElements;
}
