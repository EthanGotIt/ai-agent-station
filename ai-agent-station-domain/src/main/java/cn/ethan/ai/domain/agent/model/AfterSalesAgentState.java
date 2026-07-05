package cn.ethan.ai.domain.agent.model;

import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.model.valobj.enums.ToolErrorType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 售后Agent状态。
 * <p>当前保留Map-based内部结构作为兼容层，后续将逐步迁移到强类型聚合。</p>
 */
public record AfterSalesAgentState(Map<String, Object> data) {

    public static final String RUN_ID = "runId";
    public static final String TURN_ID = "turnId";
    public static final String CASE_ID = "caseId";
    public static final String SESSION_ID = "sessionId";
    public static final String USER_ID = "userId";
    public static final String USER_MESSAGE = "userMessage";
    public static final String ORDER_ID = "orderId";
    public static final String ORDER_OWNER_ID = "orderOwnerId";
    public static final String ORDER_STATUS = "orderStatus";
    public static final String REFUND_REASON = "refundReason";
    public static final String DAYS_SINCE_DELIVERY = "daysSinceDelivery";
    public static final String STAGE = "stage";
    public static final String ELIGIBLE = "eligible";
    public static final String TERMINAL_REASON = "terminalReason";
    public static final String DECISION_REASON = "decisionReason";
    public static final String TOOL_NAME = "toolName";
    public static final String TOOL_OUTPUT = "toolOutput";
    public static final String APPROVAL_DECISION = "approvalDecision";
    public static final String COMMAND_ID = "commandId";
    public static final String NEED_USER_INPUT = "needUserInput";
    public static final String ERROR_TYPE = "errorType";
    public static final String REPAIR_COUNT = "repairCount";
    public static final String RETRY_COUNT = "retryCount";
    public static final String RELOAD_COUNT = "reloadCount";
    public static final String REPLAN_COUNT = "replanCount";
    public static final String LAST_ERROR_TYPE = "lastErrorType";
    public static final String LAST_ERROR_MESSAGE = "lastErrorMessage";
    public static final String SAME_FAILURE_REPEATED = "sameFailureRepeated";
    public static final String CHECKLIST = "checklist";

    public AfterSalesAgentState(Map<String, Object> data) {
        this.data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
    }

    @Override
    public Map<String, Object> data() {
        return Collections.unmodifiableMap(data);
    }

    public String text(String key) {
        Object value = data.get(key);
        return value == null ? null : value.toString();
    }

    public int count(String key) {
        Object value = data.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    public Integer nullableInteger(String key) {
        return data.containsKey(key) ? count(key) : null;
    }

    public boolean flag(String key) {
        Object value = data.get(key);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    public AfterSalesStage stage() {
        return enumValue(STAGE, AfterSalesStage.class, AfterSalesStage.INTAKE);
    }

    public ToolErrorType errorType() {
        return enumValue(ERROR_TYPE, ToolErrorType.class, null);
    }

    public boolean hasText(String key) {
        String value = text(key);
        return value != null && !value.isBlank();
    }

    private <E extends Enum<E>> E enumValue(String key, Class<E> type, E fallback) {
        String value = text(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
