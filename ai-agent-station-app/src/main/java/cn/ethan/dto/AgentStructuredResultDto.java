package cn.ethan.dto;

import cn.ethan.core.agent.model.StructuredResultModel;

import java.util.Map;

/**
 * Agent 结构化结果 DTO：前端按 cardType 使用代码模板渲染，不依赖模型拼接展示格式。
 *
 * @author ethan
 * @date 2026-08-07
 */
public record AgentStructuredResultDto(String schemaVersion, String cardType, Map<String, Object> data) {

    public static AgentStructuredResultDto from(StructuredResultModel result) {
        return result == null ? null : new AgentStructuredResultDto(
                result.schemaVersion(), result.cardType(), result.data()
        );
    }
}
