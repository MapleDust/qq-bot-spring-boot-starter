package top.fcidd.qqbot.starter.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;

@UtilityClass
public class JsonUtils {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            // 网关事件会随平台迭代新增字段，未知字段直接忽略，避免反序列化失败
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public String toJson(Object obj) throws JsonProcessingException {
        return MAPPER.writeValueAsString(obj);
    }

    public <T> T fromJson(String json, Class<T> cls) throws JsonProcessingException {
        return MAPPER.readValue(json, cls);
    }

    /**
     * 将已解析的通用对象（如事件 d 字段的 Map）转换为强类型对象。
     */
    public <T> T convert(Object from, Class<T> cls) {
        return MAPPER.convertValue(from, cls);
    }

    public ObjectMapper mapper() {
        return MAPPER;
    }
}
