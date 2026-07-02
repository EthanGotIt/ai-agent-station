package cn.ethan.ai.domain.agent.model.valobj.enums;

public enum ToolErrorType {
    ARGUMENT_INVALID,
    TIMEOUT,
    RATE_LIMITED,
    TEMPORARY_UNAVAILABLE,
    STATE_CONFLICT,
    FORBIDDEN,
    BUSINESS_REJECTED,
    UNKNOWN
}
