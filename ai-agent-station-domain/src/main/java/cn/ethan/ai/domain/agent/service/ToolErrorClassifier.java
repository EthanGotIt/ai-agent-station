package cn.ethan.ai.domain.agent.service;

import cn.ethan.ai.domain.agent.model.valobj.enums.ToolErrorType;

import java.util.Locale;

public final class ToolErrorClassifier {

    public ToolErrorType classify(String errorType) {
        String value = errorType == null ? "" : errorType.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "TOOL_ARGUMENT_INVALID", "TOOL_ARGUMENT_CONFLICT", "ARGUMENT_INVALID" ->
                    ToolErrorType.ARGUMENT_INVALID;
            case "TIMEOUT", "TOOL_TIMEOUT" -> ToolErrorType.TIMEOUT;
            case "RATE_LIMITED", "TOOL_RATE_LIMITED" -> ToolErrorType.RATE_LIMITED;
            case "TEMPORARY_UNAVAILABLE", "TOOL_CALL_FAILED" -> ToolErrorType.TEMPORARY_UNAVAILABLE;
            case "STATE_CONFLICT", "ORDER_STATE_CONFLICT" -> ToolErrorType.STATE_CONFLICT;
            case "FORBIDDEN", "TOOL_FORBIDDEN", "TOOL_NOT_AUTHORIZED", "TOOL_NOT_ALLOWED" -> ToolErrorType.FORBIDDEN;
            case "BUSINESS_REJECTED", "ORDER_NOT_FOUND" -> ToolErrorType.BUSINESS_REJECTED;
            default -> ToolErrorType.UNKNOWN;
        };
    }
}
