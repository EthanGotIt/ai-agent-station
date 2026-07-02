package cn.ethan.ai.domain.agent.service;

import cn.ethan.ai.domain.agent.model.valobj.enums.ToolErrorType;
import cn.ethan.ai.domain.agent.model.valobj.enums.ToolRecoveryAction;

import java.util.Objects;

public final class ToolRecoveryPolicy {

    public static final int MAX_REPAIR_COUNT = 2;
    public static final int MAX_RETRY_COUNT = 2;
    public static final int MAX_RELOAD_COUNT = 1;

    public ToolRecoveryDecision decide(ToolErrorType errorType,
                                       int repairCount,
                                       int retryCount,
                                       int reloadCount,
                                       boolean sameFailureRepeated) {
        ToolErrorType resolvedErrorType = Objects.requireNonNullElse(errorType, ToolErrorType.UNKNOWN);
        if (sameFailureRepeated) {
            return stop("REPEATED_IDENTICAL_FAILURE");
        }
        return switch (resolvedErrorType) {
            case ARGUMENT_INVALID -> repairCount < MAX_REPAIR_COUNT
                    ? new ToolRecoveryDecision(ToolRecoveryAction.REPAIR, "REPAIR_INVALID_ARGUMENTS")
                    : stop("REPAIR_BUDGET_EXHAUSTED");
            case TIMEOUT, RATE_LIMITED, TEMPORARY_UNAVAILABLE -> retryCount < MAX_RETRY_COUNT
                    ? new ToolRecoveryDecision(ToolRecoveryAction.RETRY, "RETRY_TRANSIENT_FAILURE")
                    : stop("RETRY_BUDGET_EXHAUSTED");
            case STATE_CONFLICT -> reloadCount < MAX_RELOAD_COUNT
                    ? new ToolRecoveryDecision(ToolRecoveryAction.RELOAD, "RELOAD_CONFLICTED_STATE")
                    : stop("RELOAD_BUDGET_EXHAUSTED");
            case FORBIDDEN -> stop("TOOL_ACCESS_FORBIDDEN");
            case BUSINESS_REJECTED -> stop("BUSINESS_RULE_REJECTED");
            case UNKNOWN -> stop("UNKNOWN_TOOL_FAILURE");
        };
    }

    private ToolRecoveryDecision stop(String reason) {
        return new ToolRecoveryDecision(ToolRecoveryAction.STOP, reason);
    }

    public record ToolRecoveryDecision(ToolRecoveryAction action, String reason) {
    }
}
