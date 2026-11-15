package top.fcidd.qqbot.starter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdentifyPayload {
    private String token;
    private Integer intents;
    private Integer shardIndex;
    private Integer shardCount;
}

