package top.fcidd.qqbot.starter.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class BotEvent {
    private Integer op;
    private String t;
    private Long s;
    private Object d;

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
