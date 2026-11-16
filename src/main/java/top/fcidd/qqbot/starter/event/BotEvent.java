package top.fcidd.qqbot.starter.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BotEvent {
    /** 事件 ID，平台下发的唯一标识 */
    private String id;
    private Integer op;
    private String t;
    private Long s;
    private Object d;

    @JsonProperty("id")
    public String getId() {
        return id;
    }

    @JsonProperty("op")
    public Integer getOp() {
        return op;
    }

    @JsonProperty("t")
    public String getT() {
        return t;
    }

    @JsonProperty("s")
    public Long getS() {
        return s;
    }

    @JsonProperty("d")
    public Object getD() {
        return d;
    }
}
