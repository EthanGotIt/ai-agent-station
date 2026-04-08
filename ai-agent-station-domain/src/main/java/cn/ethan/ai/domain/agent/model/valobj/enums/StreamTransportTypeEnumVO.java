package cn.ethan.ai.domain.agent.model.valobj.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Locale;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum StreamTransportTypeEnumVO {
    SSE("sse", "SSE HTTP协议"),
    STREAMABLE_HTTP("streamable_http", "Streamable HTTP协议");

    private String code;
    private String info;

    public static StreamTransportTypeEnumVO fromCode(String code) {
        if (code == null || code.isBlank()) {
            return STREAMABLE_HTTP;
        }
        String normalizedCode = code.toLowerCase(Locale.ROOT);
        for (StreamTransportTypeEnumVO type : values()) {
            if (type.code.equals(normalizedCode)) {
                return type;
            }
        }
        return STREAMABLE_HTTP;
    }
}
