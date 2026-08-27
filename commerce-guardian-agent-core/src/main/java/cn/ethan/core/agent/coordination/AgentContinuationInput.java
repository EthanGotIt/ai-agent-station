package cn.ethan.core.agent.coordination;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 类型职责：描述一次由已持久化业务结果触发的 Agent 续跑 Turn，保证恢复和去重不依赖瞬时内存状态。
 *
 * @author ethan
 * @date 2026-08-26
 */
public record AgentContinuationInput(
        String rootTurnId,
        String parentTurnId,
        String triggerRunId,
        String triggerCommandId,
        String triggerStatus,
        long triggerSequence,
        int cycleNo
) {

    public static final int MAX_TRIGGER_STATUS_LENGTH = 64;

    public AgentContinuationInput {
        rootTurnId = required(rootTurnId, "rootTurnId");
        parentTurnId = required(parentTurnId, "parentTurnId");
        triggerRunId = required(triggerRunId, "triggerRunId");
        triggerCommandId = optional(triggerCommandId);
        triggerStatus = required(triggerStatus, "triggerStatus").toUpperCase(Locale.ROOT);
        if (triggerStatus.length() > MAX_TRIGGER_STATUS_LENGTH) {
            throw new IllegalArgumentException("triggerStatus 过长");
        }
        if (triggerSequence < 0) {
            throw new IllegalArgumentException("triggerSequence 不能为负数");
        }
        if (cycleNo < 1) {
            throw new IllegalArgumentException("cycleNo 必须从 1 开始");
        }
    }

    /**
     * 生成跨 Worker/Workflow 共享的请求幂等键；所有触发事实都参与计算，避免同一命令不同结果重复续跑。
     */
    public String idempotencyKey() {
        String canonical = String.join("|", rootTurnId, parentTurnId, triggerRunId,
                triggerCommandId == null ? "-" : triggerCommandId, triggerStatus,
                Long.toString(triggerSequence), Integer.toString(cycleNo));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder result = new StringBuilder("continuation:");
            for (byte value : digest.digest(canonical.getBytes(StandardCharsets.UTF_8))) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缺少 SHA-256", failure);
        }
    }

    private static String required(String value, String name) {
        String normalized = optional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }

    private static String optional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
