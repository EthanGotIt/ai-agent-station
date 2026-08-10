package cn.ethan.infrastructure.qwen.support;

import cn.ethan.core.agent.model.AgentMemoryCandidateModel;

import java.util.List;

/**
 * Qwen 记忆提取的结构化响应包装，避免将自由文本直接写入记忆存储。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record QwenMemoryExtractionResponse(List<AgentMemoryCandidateModel> candidates) {

    public QwenMemoryExtractionResponse {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
