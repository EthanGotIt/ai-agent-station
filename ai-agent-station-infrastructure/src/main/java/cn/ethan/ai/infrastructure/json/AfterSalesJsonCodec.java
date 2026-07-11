package cn.ethan.ai.infrastructure.json;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 售后运行时统一 JSON 边界，避免序列化框架异常泄漏到领域层。
 */
@Component
public final class AfterSalesJsonCodec {

    private final JsonMapper jsonMapper;

    public AfterSalesJsonCodec(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public static AfterSalesJsonCodec defaultCodec() {
        return new AfterSalesJsonCodec(JsonMapper.builderWithJackson2Defaults().build());
    }

    public String write(Object value, String operation) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException error) {
            throw invalidJson(operation, error);
        }
    }

    public String writePretty(Object value, String operation) {
        try {
            return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JacksonException error) {
            throw invalidJson(operation, error);
        }
    }

    public <T> T read(String value, Class<T> type, String operation) {
        try {
            return jsonMapper.readValue(value, type);
        } catch (JacksonException error) {
            throw invalidJson(operation, error);
        }
    }

    public <T> T read(String value, TypeReference<T> type, String operation) {
        try {
            return jsonMapper.readValue(value, type);
        } catch (JacksonException error) {
            throw invalidJson(operation, error);
        }
    }

    private IllegalArgumentException invalidJson(String operation, JacksonException error) {
        return new IllegalArgumentException(operation + " JSON 处理失败", error);
    }
}
