package top.fcidd.qqbot.starter.model.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 消息场景上下文。
 * ext 为 key=value 形式的扩展列表，常见 key：
 * msg_idx=消息索引、ref_msg_idx=引用的消息索引、auth_token=鉴权令牌。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageScene {

    /** 场景来源。default=默认聊天窗口 */
    @JsonProperty("source")
    private String source;

    /** 扩展数据列表，key=value 格式 */
    @JsonProperty("ext")
    private List<String> ext;
}
