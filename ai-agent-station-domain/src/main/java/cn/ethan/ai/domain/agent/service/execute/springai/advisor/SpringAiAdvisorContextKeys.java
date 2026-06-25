package cn.ethan.ai.domain.agent.service.execute.springai.advisor;

/**
 * Spring AI 2 Advisor Chain 共享上下文键常量。
 */
public final class SpringAiAdvisorContextKeys {

    public static final String CONTEXT_UNITS = "ai_agent_context_units";

    public static final String CONTEXT_BUDGET_REJECTED = "ai_agent_context_budget_rejected";

    public static final String EVIDENCE_ACCUMULATOR = "ai_agent_evidence_accumulator";

    public static final String RAG_EVIDENCE_TRACE = "ai_agent_rag_evidence_trace";

    private SpringAiAdvisorContextKeys() {
    }
}
