package cn.ethan.core.agent.context;

import cn.ethan.core.agent.thread.AgentItemModel;

import java.util.List;

/**
 * 类型职责：描述一次只读上下文摘要请求，禁止携带原始 Thinking 或外部写操作。
 *
 * @author ethan
 * @date 2026-09-05
 */
public record AgentContextSummaryRequest(
        String threadId,
        String previousSummary,
        List<AgentItemModel> facts,
        long fromSequence,
        long throughSequence,
        String promptVersion,
        int maxOutputTokens
) {

    public AgentContextSummaryRequest {
        previousSummary = previousSummary == null ? "" : previousSummary;
        facts = facts == null ? List.of() : List.copyOf(facts);
        promptVersion = promptVersion == null || promptVersion.isBlank()
                ? "context-summary-v2" : promptVersion;
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("summary maxOutputTokens must be positive");
        }
    }
}
