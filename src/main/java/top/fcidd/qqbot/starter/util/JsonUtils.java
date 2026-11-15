package top.fcidd.qqbot.starter.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;

@UtilityClass
public class JsonUtils {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String toJson(Object obj) throws JsonProcessingException {
        return MAPPER.writeValueAsString(obj);
    }

    public <T> T fromJson(String json, Class<T> cls) throws JsonProcessingException {
        return MAPPER.readValue(json, cls);
    }
}