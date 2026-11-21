package top.fcidd.qqbot.starter.event;

import lombok.extern.log4j.Log4j2;
import top.fcidd.qqbot.starter.model.message.ArkData;
import top.fcidd.qqbot.starter.model.message.MessageAttachment;
import top.fcidd.qqbot.starter.model.message.MessagePayload;
import top.fcidd.qqbot.starter.model.message.MessageUser;
import top.fcidd.qqbot.starter.util.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 消息事件日志打印：群聊 @机器人、单聊、群聊全量消息。
 * <p>
 * 事件与字段依据 QQ 机器人 API v2：
 * <ul>
 *     <li>C2C_MESSAGE_CREATE — 用户单聊给机器人发消息</li>
 *     <li>GROUP_AT_MESSAGE_CREATE — 用户在群内 @机器人</li>
 *     <li>GROUP_MESSAGE_CREATE — 群聊全量消息（群主开启“接收所有消息”后）</li>
 * </ul>
 */
@Log4j2
public class MessageEventLogger {

    public static final String GROUP_AT_MESSAGE_CREATE = "GROUP_AT_MESSAGE_CREATE";
    public static final String GROUP_MESSAGE_CREATE = "GROUP_MESSAGE_CREATE";
    public static final String C2C_MESSAGE_CREATE = "C2C_MESSAGE_CREATE";

    /** 机器人入群 / 被移出群 */
    public static final String GROUP_ADD_ROBOT = "GROUP_ADD_ROBOT";
    public static final String GROUP_DEL_ROBOT = "GROUP_DEL_ROBOT";
    /** 群管理员在机器人资料页开启 / 关闭消息通知（可据此判断群全量消息是否放开） */
    public static final String GROUP_MSG_RECEIVE = "GROUP_MSG_RECEIVE";
    public static final String GROUP_MSG_REJECT = "GROUP_MSG_REJECT";

    /** 内容过长时截断，避免日志被刷爆 */
    private static final int MAX_CONTENT_LENGTH = 300;
    /** 附件过多时只打印前几个 */
    private static final int MAX_ATTACHMENTS_LOGGED = 5;
    /** @成员过多时只打印前几个 */
    private static final int MAX_MENTIONS_LOGGED = 3;

    /**
     * 是否是需要打印的消息事件类型。
     */
    public static boolean supports(String eventType) {
        return GROUP_AT_MESSAGE_CREATE.equals(eventType)
                || GROUP_MESSAGE_CREATE.equals(eventType)
                || C2C_MESSAGE_CREATE.equals(eventType);
    }

    /**
     * 打印消息事件日志。非消息事件（或非 Dispatch 事件）直接跳过。
     */
    public void handle(BotEvent event) {
        String message = format(event);
        if (message != null) {
            log.info(message);
        }
    }

    /**
     * 将消息事件格式化为一行日志文本；不是消息事件时返回 {@code null}。
     */
    String format(BotEvent event) {
        if (event == null || event.getT() == null || event.getD() == null) {
            return null;
        }
        if (event.getOp() == null || event.getOp() != 0) {
            return null;
        }
        String eventType = event.getT();
        if (!supports(eventType) && !isGroupStatusEvent(eventType)) {
            return null;
        }
        try {
            return switch (eventType) {
                case GROUP_ADD_ROBOT -> formatGroupStatus(event, "机器人被添加到群");
                case GROUP_DEL_ROBOT -> formatGroupStatus(event, "机器人被移出群");
                case GROUP_MSG_RECEIVE -> formatGroupStatus(event, "群消息接收开关：开启");
                case GROUP_MSG_REJECT -> formatGroupStatus(event, "群消息接收开关：关闭");
                case GROUP_AT_MESSAGE_CREATE -> formatMessage(event, eventType,
                        JsonUtils.convert(event.getD(), MessagePayload.class));
                case GROUP_MESSAGE_CREATE -> formatMessage(event, eventType,
                        JsonUtils.convert(event.getD(), MessagePayload.class));
                case C2C_MESSAGE_CREATE -> formatMessage(event, eventType,
                        JsonUtils.convert(event.getD(), MessagePayload.class));
                default -> null;
            };
        } catch (IllegalArgumentException ex) {
            log.warn("解析消息事件失败，事件类型={}，原始事件内容={}", eventType, event.getD(), ex);
            return null;
        }
    }

    private boolean isGroupStatusEvent(String eventType) {
        return GROUP_ADD_ROBOT.equals(eventType)
                || GROUP_DEL_ROBOT.equals(eventType)
                || GROUP_MSG_RECEIVE.equals(eventType)
                || GROUP_MSG_REJECT.equals(eventType);
    }

    /**
     * 群状态类事件（入群/退群/消息开关）日志。
     * 如果群主刚在手机 QQ 里把「机器人可获取的群聊消息范围」改成全部消息，这里应当能打出「群消息接收开关：开启」，
     * 随后群里非 @ 机器人的消息才会以 GROUP_MESSAGE_CREATE 推送。
     */
    private String formatGroupStatus(BotEvent event, String action) {
        String groupOpenid = null;
        Object data = event.getD();
        if (data instanceof Map<?, ?> map) {
            Object value = map.get("group_openid");
            groupOpenid = value == null ? null : String.valueOf(value);
        }
        return "[群状态] " + action
                + " 事件ID=" + event.getId()
                + " 序号s=" + event.getS()
                + " 群openid=" + groupOpenid;
    }

    /**
     * 统一格式化三类消息事件。群聊/单聊由 {@link MessagePayload#isGroup()} 自动判断，
     * 群聊里再按“是否 @ 机器人”区分「群聊@机器人」与「群聊全量消息」。
     */
    private String formatMessage(BotEvent event, String eventType, MessagePayload payload) {
        if (payload == null) {
            return null;
        }
        boolean c2c = C2C_MESSAGE_CREATE.equals(eventType);
        boolean groupScene = !c2c && payload.isGroup();
        boolean fromAtEvent = GROUP_AT_MESSAGE_CREATE.equals(eventType);
        // 全量模式下 @机器人 的消息也走 GROUP_MESSAGE_CREATE，场景要按“是否 @ 机器人”判断
        boolean atBot = fromAtEvent || (groupScene && payload.mentionsBot());
        String sceneLabel = groupScene ? (atBot ? "群聊@机器人" : "群聊全量消息") : "单聊";
        String sceneNote = atBot && !fromAtEvent ? "（全量模式下收到，按@机器人处理）" : "";
        return "[" + sceneLabel + "]" + sceneNote
                + " 事件类型=" + eventType
                + " 事件ID=" + event.getId()
                + " 序号s=" + event.getS()
                + " 消息ID=" + payload.getId()
                + (groupScene ? " 群openid=" + payload.getGroupOpenid() : "")
                + " 发送者=" + describeUser(payload.getAuthor(), groupScene)
                + " 类型=" + describeMessageType(payload.getMessageType())
                + " 内容=" + describeContent(payload.getContent())
                + " 附件=" + describeAttachments(payload.getAttachments())
                + (groupScene ? " @成员=" + describeMentions(payload.getMentions()) : "")
                + describeArk(payload.getArkData())
                + " 时间=" + payload.getTimestamp();
    }

    private String describeUser(MessageUser user, boolean groupScene) {
        if (user == null) {
            return "未知";
        }
        StringJoiner joiner = new StringJoiner(", ");
        if (groupScene && user.getMemberOpenid() != null && !user.getMemberOpenid().isBlank()) {
            joiner.add("member_openid=" + user.getMemberOpenid());
        } else if (!groupScene && user.getUserOpenid() != null && !user.getUserOpenid().isBlank()) {
            joiner.add("user_openid=" + user.getUserOpenid());
        }
        if (user.getUnionOpenid() != null && !user.getUnionOpenid().isBlank()) {
            joiner.add("union_openid=" + user.getUnionOpenid());
        }
        if (groupScene && user.getMemberRole() != null && !user.getMemberRole().isBlank()) {
            joiner.add("身份=" + describeRole(user.getMemberRole()));
        }
        if (Boolean.TRUE.equals(user.getBot())) {
            joiner.add("bot=true");
        }
        return joiner.length() == 0
                ? user.displayName()
                : user.displayName() + "(" + joiner + ")";
    }

    private String describeRole(String role) {
        return switch (role) {
            case "owner" -> "群主(owner)";
            case "admin" -> "管理员(admin)";
            case "member" -> "成员(member)";
            default -> role;
        };
    }

    private String describeMessageType(Integer messageType) {
        if (messageType == null) {
            return "未知";
        }
        String label = switch (messageType) {
            case 0 -> "文本";
            case 2 -> "Markdown";
            case 3 -> "结构化卡片";
            case 7 -> "富媒体";
            case 101 -> "并行消息";
            case 102 -> "聊天记录";
            case 103 -> "引用消息";
            default -> "其他";
        };
        return messageType + "(" + label + ")";
    }

    private String describeContent(String content) {
        if (content == null) {
            return "(无内容字段)";
        }
        if (content.isBlank()) {
            return "(空)";
        }
        String normalized = content.replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n")
                .replace("\t", "\\t");
        int length = normalized.length();
        if (length > MAX_CONTENT_LENGTH) {
            normalized = normalized.substring(0, MAX_CONTENT_LENGTH) + "…(共" + length + "字符)";
        }
        return "\"" + normalized + "\"";
    }

    private String describeAttachments(List<MessageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "无";
        }
        List<String> items = new ArrayList<>();
        int printed = 0;
        for (int i = 0; i < attachments.size() && printed < MAX_ATTACHMENTS_LOGGED; i++) {
            MessageAttachment attachment = attachments.get(i);
            if (attachment == null) {
                continue;
            }
            printed++;
            StringJoiner joiner = new StringJoiner(" ");
            joiner.add(attachment.getContentType() == null ? "未知类型" : attachment.getContentType());
            if (attachment.getFilename() != null && !attachment.getFilename().isBlank()) {
                joiner.add(attachment.getFilename());
            }
            if (attachment.getWidth() != null && attachment.getHeight() != null) {
                joiner.add(attachment.getWidth() + "x" + attachment.getHeight());
            }
            if (attachment.getSize() != null) {
                joiner.add(attachment.getSize() + "B");
            }
            if (attachment.getUrl() != null && !attachment.getUrl().isBlank()) {
                // 附件 URL 带有鉴权参数，不直接打印，只标识可下载
                joiner.add("可下载");
            }
            if (attachment.getAsrReferText() != null && !attachment.getAsrReferText().isBlank()) {
                joiner.add("asr=" + describeContent(attachment.getAsrReferText()));
            }
            items.add("{" + joiner + "}");
        }
        String suffix = attachments.size() > MAX_ATTACHMENTS_LOGGED
                ? "…共" + attachments.size() + "个"
                : "";
        return printed + "个" + String.join(" ", items) + suffix;
    }

    private String describeMentions(List<MessageUser> mentions) {
        if (mentions == null || mentions.isEmpty()) {
            return "无";
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < mentions.size() && i < MAX_MENTIONS_LOGGED; i++) {
            MessageUser mention = mentions.get(i);
            if (mention != null) {
                names.add(Boolean.TRUE.equals(mention.getIsYou()) ? mention.displayName() + "(@我)" : mention.displayName());
            }
        }
        String suffix = mentions.size() > MAX_MENTIONS_LOGGED
                ? "…共" + mentions.size() + "人"
                : "";
        return mentions.size() + "人" + String.join("、", names) + suffix;
    }

    private String describeArk(ArkData arkData) {
        if (arkData == null) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(", ");
        if (arkData.getArkName() != null && !arkData.getArkName().isBlank()) {
            joiner.add(arkData.getArkName());
        }
        if (arkData.getArkType() != null && !arkData.getArkType().isBlank()) {
            joiner.add(arkData.getArkType());
        }
        if (arkData.getPrompt() != null && !arkData.getPrompt().isBlank()) {
            joiner.add("prompt=" + describeContent(arkData.getPrompt()));
        }
        return joiner.length() == 0 ? "" : " 卡片=" + joiner;
    }
}
