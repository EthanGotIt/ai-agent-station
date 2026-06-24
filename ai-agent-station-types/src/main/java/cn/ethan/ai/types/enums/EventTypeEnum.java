package cn.ethan.ai.types.enums;

/**
 * LLM call event type constants — used by step nodes when emitting result entities.
 */
public enum EventTypeEnum {

    LLM_CALL_PLAN("LLM_CALL_PLAN"),
    LLM_CALL_PLAN_REPAIR("LLM_CALL_PLAN_REPAIR"),
    LLM_CALL_STEP("LLM_CALL_STEP"),
    LLM_CALL_SUPERVISION("LLM_CALL_SUPERVISION"),
    LLM_CALL_SUMMARY("LLM_CALL_SUMMARY"),
    LOCAL_SUMMARY("LOCAL_SUMMARY"),
    ANALYSIS_TOOLS("analysis_tools"),
    ANALYSIS_PLAN("analysis_plan"),
    CONTEXT_BOUNDARY("context_boundary"),
    TOOL_ROUTING("tool_routing"),
    EXECUTION_TARGET("execution_target"),
    CONTEXT_GUARD("context_guard"),
    RAG_EVIDENCE("rag_evidence"),
    EXECUTION_QUALITY("execution_quality"),
    CANCELLED("cancelled");

    private final String value;

    EventTypeEnum(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

}
