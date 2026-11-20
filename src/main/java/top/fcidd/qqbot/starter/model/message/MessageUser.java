package top.fcidd.qqbot.starter.model.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 消息发送者 / 被 @ 的用户对象。
 * 文档：https://bot.q.qq.com/wiki/develop/api-v2/autogen/event/group_at_message_create.html
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageUser {

    /** 用户唯一标识（OpenID 格式） */
    @JsonProperty("id")
    private String id;

    /** 用户昵称 */
    @JsonProperty("username")
    private String username;

    /** 是否为机器人 */
    @JsonProperty("bot")
    private Boolean bot;

    /** 跨应用统一用户 OpenID（可能为空） */
    @JsonProperty("union_openid")
    private String unionOpenid;

    /** 跨应用统一用户账号（可能为空） */
    @JsonProperty("union_user_account")
    private String unionUserAccount;

    /** 用户 OpenID（单聊场景使用） */
    @JsonProperty("user_openid")
    private String userOpenid;

    /** 群成员 OpenID（群聊场景使用） */
    @JsonProperty("member_openid")
    private String memberOpenid;

    /** 群内角色：member=普通成员, admin=管理员, owner=群主 */
    @JsonProperty("member_role")
    private String memberRole;

    /** 群内昵称（群聊场景） */
    @JsonProperty("member_nickname")
    private String memberNickname;

    /** mentions 专用：是否是 @ 的当前机器人 */
    @JsonProperty("is_you")
    private Boolean isYou;

    /** mentions 专用：all=@全体成员，single=@指定对象 */
    @JsonProperty("scope")
    private String scope;

    /** 用于日志展示的用户标识：优先昵称，其次 openid */
    public String displayName() {
        if (username != null && !username.isBlank()) {
            return username;
        }
        String openid = memberOpenid != null && !memberOpenid.isBlank() ? memberOpenid : userOpenid;
        return openid == null || openid.isBlank() ? "未知用户" : openid;
    }
}
