package cn.ethan.core.agent.model;

import java.util.Map;

/**
 * 结构化结果模型：承载后端代码格式化后的稳定卡片数据。
 *
 * @author ethan
 * @date 2026-08-07
 */
public record StructuredResultModel(
        String schemaVersion,
        String cardType,
        Map<String, Object> data
) {

    public StructuredResultModel {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "1" : schemaVersion;
        if (cardType == null || cardType.isBlank()) {
            throw new IllegalArgumentException("cardType is required");
        }
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
