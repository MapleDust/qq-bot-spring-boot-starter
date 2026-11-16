package top.fcidd.qqbot.starter.model.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * 结构化卡片（ARK）消息数据，message_type=3 时有值。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArkData {

    /** 卡片消息中的用户操作提示文本 */
    @JsonProperty("prompt")
    private String prompt;

    /**
     * 卡片类型标识：tuwen=图文 H5、feed=图文卡片、miniapp=小程序、
     * map=位置卡片、contact_card=好友名片、video_share=视频分享、
     * music_together=一起听歌、picture=图片
     */
    @JsonProperty("ark_type")
    private String arkType;

    /** 卡片类型中文名，如“图文 H5”“小程序” */
    @JsonProperty("ark_name")
    private String arkName;

    /** 卡片字段：title/desc/jump_url/preview/source/... */
    @JsonProperty("fields")
    private Map<String, Object> fields;
}
