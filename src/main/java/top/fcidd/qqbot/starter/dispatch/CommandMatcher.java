package top.fcidd.qqbot.starter.dispatch;

import lombok.experimental.UtilityClass;

/**
 * 指令匹配工具：判断消息内容是否命中指令前缀，并取出指令后的参数。
 */
@UtilityClass
public class CommandMatcher {

    /**
     * 消息内容是否命中指令。指令为空表示匹配全部消息。
     * 命中时要求指令后是结尾或非字母/数字字符，避免 /ping 误匹配 /pingpong。
     */
    public boolean matches(String content, String command, boolean ignoreCase) {
        if (command == null || command.isBlank()) {
            return true;
        }
        if (content == null) {
            return false;
        }
        String text = content.trim();
        String cmd = command.trim();
        if (cmd.isEmpty()) {
            return true;
        }
        if (text.length() < cmd.length()) {
            return false;
        }
        if (!text.regionMatches(ignoreCase, 0, cmd, 0, cmd.length())) {
            return false;
        }
        if (text.length() == cmd.length()) {
            return true;
        }
        char last = cmd.charAt(cmd.length() - 1);
        char next = text.charAt(cmd.length());
        return !(Character.isLetterOrDigit(last) && Character.isLetterOrDigit(next));
    }

    /**
     * 取出指令之后的内容（已去首尾空白），未命中指令时返回原文。
     */
    public String extractArgs(String content, String command, boolean ignoreCase) {
        if (content == null) {
            return "";
        }
        String text = content.trim();
        String cmd = command == null ? "" : command.trim();
        if (cmd.isEmpty() || !matches(text, cmd, ignoreCase)) {
            return text;
        }
        return text.substring(cmd.length()).trim();
    }
}
