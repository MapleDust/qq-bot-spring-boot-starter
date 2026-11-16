package top.fcidd.qqbot.starter.sender;

import top.fcidd.qqbot.starter.util.JsonUtils;

import java.util.Map;

/**
 * 发送消息的结果。
 *
 * @param success    是否发送成功
 * @param httpStatus HTTP 状态码，0 表示请求未发出或网络异常
 * @param messageId  成功时返回的平台消息 ID
 * @param code       失败时的平台错误码，-1 表示本地错误
 * @param message    失败原因或平台返回的描述
 * @param raw        平台原始响应
 */
public record SendResult(boolean success, int httpStatus, String messageId, int code, String message, String raw) {

    private static final int LOCAL_ERROR = -1;

    public static SendResult from(int httpStatus, String raw) {
        String messageId = null;
        int code = 0;
        String message = null;
        try {
            Map<?, ?> body = JsonUtils.fromJson(raw, Map.class);
            if (body != null) {
                Object id = body.get("id");
                messageId = id == null ? null : String.valueOf(id);
                Object codeValue = body.get("code");
                if (codeValue instanceof Number number) {
                    code = number.intValue();
                }
                Object desc = body.get("message");
                message = desc == null ? null : String.valueOf(desc);
            }
        } catch (Exception ignored) {
            // 非 JSON 响应，保留 raw 供排查
        }
        boolean success = httpStatus >= 200 && httpStatus < 300 && messageId != null;
        return new SendResult(success, httpStatus, messageId, code, message, raw);
    }

    public static SendResult failure(String message) {
        return new SendResult(false, 0, null, LOCAL_ERROR, message, message);
    }

    public String describe() {
        return success
                ? "发送成功，消息ID=" + messageId
                : "发送失败，http=" + httpStatus + "，code=" + code + "，message=" + message;
    }
}
