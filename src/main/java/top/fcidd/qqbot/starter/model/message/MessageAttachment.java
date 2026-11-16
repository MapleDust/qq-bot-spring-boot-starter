package top.fcidd.qqbot.starter.model.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 富媒体附件（图片、语音、视频、文件）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageAttachment {

    /** 附件下载 URL */
    @JsonProperty("url")
    private String url;

    /** 文件名 */
    @JsonProperty("filename")
    private String filename;

    /** 图片宽度（像素），非图片附件无此字段 */
    @JsonProperty("width")
    private Integer width;

    /** 图片高度（像素），非图片附件无此字段 */
    @JsonProperty("height")
    private Integer height;

    /** 文件大小（字节） */
    @JsonProperty("size")
    private Integer size;

    /** voice=语音, image/jpeg, image/png, image/gif, video/mp4, file=群文件 */
    @JsonProperty("content_type")
    private String contentType;

    /** 语音消息转换后的 WAV 文件 URL */
    @JsonProperty("voice_wav_url")
    private String voiceWavUrl;

    /** 语音消息 ASR 参考结果 */
    @JsonProperty("asr_refer_text")
    private String asrReferText;
}
