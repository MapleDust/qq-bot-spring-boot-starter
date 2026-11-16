package top.fcidd.qqbot.starter.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 机器人在指定群内的状态，对应 GET /v2/groups/{group_openid}/bot_state。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BotState {

    /** 机器人的 member_openid */
    @JsonProperty("member_openid")
    private String memberOpenid;

    /** 入群时间戳（RFC3339） */
    @JsonProperty("joined_at")
    private String joinedAt;

    /** 是否接收主动推送 */
    @JsonProperty("allow_proactive_msg")
    private Boolean allowProactiveMsg;

    /**
     * 群内接收消息的设置：
     * all=群内全部消息、only_mention=仅 @机器人、mention_and_context=@机器人及上下文。
     */
    @JsonProperty("recv_msg_setting")
    private String recvMsgSetting;

    /** 群内角色：member / admin / owner */
    @JsonProperty("member_role")
    private String memberRole;

    /** 是否已开启接收群内全部消息（全量模式的前置条件） */
    public boolean receiveAllMessages() {
        return "all".equals(recvMsgSetting);
    }

    public String describe() {
        String setting = switch (recvMsgSetting == null ? "" : recvMsgSetting) {
            case "all" -> "获取群内全部消息";
            case "only_mention" -> "仅获取@机器人的消息";
            case "mention_and_context" -> "获取@机器人及上下文消息";
            default -> recvMsgSetting;
        };
        return "群内角色=" + memberRole
                + "，入群时间=" + joinedAt
                + "，可接收主动推送=" + allowProactiveMsg
                + "，消息范围=" + setting
                + "（群全量消息" + (receiveAllMessages() ? "已开启" : "未开启") + "）";
    }
}
