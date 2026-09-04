package cn.ethan.core.agent.execution;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 类型职责：在 Turn、模型协调和 Tool 边界之间传播取消与截止时间，不回滚已经提交的副作用。
 *
 * @author ethan
 * @date 2026-08-20
 */
public final class AgentExecutionContext {

    private final Clock clock;
    private final Instant deadline;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final int maxOutputTokens;
    private final AgentToolFailureCircuitBreaker toolFailureCircuitBreaker;
    private final AtomicReference<AgentExecutionStopReasonEnum> stopReason = new AtomicReference<>();
    private final Map<String, Integer> outputReservations = new HashMap<>();
    private long outputTokensReserved;
    private long outputTokensUsed;
    private int contextBudget = Integer.MAX_VALUE;
    private int contextTokensUsed;

    public AgentExecutionContext(Clock clock, Instant deadline) {
        this(clock, deadline, 8_192, 3);
    }

    public AgentExecutionContext(
            Clock clock,
            Instant deadline,
            int maxOutputTokens,
            int repeatedToolFailureThreshold
    ) {
        this.clock = clock;
        this.deadline = deadline;
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        this.maxOutputTokens = maxOutputTokens;
        this.toolFailureCircuitBreaker = new AgentToolFailureCircuitBreaker(repeatedToolFailureThreshold);
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean cancelled() {
        return cancelled.get() || clock.instant().compareTo(deadline) >= 0;
    }

    public void checkActive() {
        if (cancelled()) {
            throw new AgentExecutionCancelledException("Agent Turn 已取消或超过执行截止时间");
        }
    }

    /**
     * 为一次实际模型请求预留输出额度。预留不会因缺失 usage 或断流自动退回。
     */
    public synchronized String reserveOutput(int requestedTokens) {
        checkActive();
        long available = maxOutputTokens - outputTokensUsed - outputTokensReserved;
        if (available <= 0) {
            markStopped(AgentExecutionStopReasonEnum.OUTPUT_BUDGET_EXCEEDED);
            return null;
        }
        int reservation = Math.toIntExact(Math.min(Math.max(1, requestedTokens), available));
        String reservationId = java.util.UUID.randomUUID().toString();
        outputReservations.put(reservationId, reservation);
        outputTokensReserved += reservation;
        return reservationId;
    }

    /**
     * 结算一次模型请求。usage 缺失、为零或请求断流时传入 null，保守扣除整笔预留。
     */
    public synchronized void settleOutput(String reservationId, Integer completionTokens) {
        if (reservationId == null) {
            return;
        }
        Integer reserved = outputReservations.remove(reservationId);
        if (reserved == null) {
            return;
        }
        outputTokensReserved -= reserved;
        long charged = completionTokens == null || completionTokens <= 0
                ? reserved
                : completionTokens;
        outputTokensUsed += charged;
        if (outputTokensUsed >= maxOutputTokens) {
            markStopped(AgentExecutionStopReasonEnum.OUTPUT_BUDGET_EXCEEDED);
        }
    }

    public synchronized void settleCurrentOutput(Integer completionTokens) {
        if (outputReservations.isEmpty()) {
            return;
        }
        settleOutput(outputReservations.keySet().iterator().next(), completionTokens);
    }

    /**
     * 设置本轮模型输入预算，并记录组装阶段的完整估算。
     */
    public synchronized void initializeContextBudget(int budget, int estimatedTokens) {
        contextBudget = Math.max(1, budget);
        contextTokensUsed = Math.max(0, estimatedTokens);
        if (contextTokensUsed > contextBudget) {
            markStopped(AgentExecutionStopReasonEnum.CONTEXT_BUDGET_EXCEEDED);
        }
    }

    /**
     * 在每次真实模型请求前检查包含工具结果的完整 Prompt 估算。
     */
    public synchronized boolean checkContextBudget(int estimatedTokens) {
        contextTokensUsed = Math.max(contextTokensUsed, Math.max(0, estimatedTokens));
        if (contextTokensUsed > contextBudget) {
            markStopped(AgentExecutionStopReasonEnum.CONTEXT_BUDGET_EXCEEDED);
            return false;
        }
        return true;
    }

    public synchronized boolean outputBudgetExhausted() {
        return outputTokensUsed + outputTokensReserved >= maxOutputTokens;
    }

    public synchronized long outputTokensUsed() {
        return outputTokensUsed;
    }

    public synchronized int contextBudget() {
        return contextBudget;
    }

    public synchronized int contextTokensUsed() {
        return contextTokensUsed;
    }

    public AgentExecutionStopReasonEnum stopReason() {
        return stopReason.get();
    }

    public boolean stopped() {
        return stopReason.get() != null;
    }

    public void markStopped(AgentExecutionStopReasonEnum reason) {
        if (reason != null) {
            stopReason.compareAndSet(null, reason);
        }
    }

    public boolean recordToolFailure(String tool, String arguments, String errorCode) {
        return toolFailureCircuitBreaker.recordFailure(tool, arguments, errorCode);
    }

    public void recordToolSuccess() {
        toolFailureCircuitBreaker.recordSuccess();
    }

    public int repeatedToolFailures() {
        return toolFailureCircuitBreaker.consecutiveFailures();
    }

    public Instant deadline() {
        return deadline;
    }
}
