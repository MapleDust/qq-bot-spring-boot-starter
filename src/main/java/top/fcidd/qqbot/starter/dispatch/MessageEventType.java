package top.fcidd.qqbot.starter.dispatch;

/**
 * 监听的消息事件类型。
 */
public enum MessageEventType {

    /** 群聊 @机器人：GROUP_AT_MESSAGE_CREATE */
    GROUP_AT,

    /** 群聊全量消息：GROUP_MESSAGE_CREATE */
    GROUP_MESSAGE,

    /** 单聊：C2C_MESSAGE_CREATE */
    C2C;

    public boolean isGroup() {
        return this == GROUP_AT || this == GROUP_MESSAGE;
    }
}
