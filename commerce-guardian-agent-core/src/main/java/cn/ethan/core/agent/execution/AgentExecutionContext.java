package cn.ethan.core.agent.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private int contextTokensPeak;
    private String contextViewKey;
    private final Map<String, ContextCompactionAttempt> contextCompactionAttempts = new LinkedHashMap<>();
    private PromptMeasurement promptMeasurement;
    private PromptMeasurement pendingOverflowRecoveryBaseline;
    private int contextOverflowRetries;

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

    /** 释放尚未发送的输出预留，不计入已用额度，也不设置资源停止原因。 */
    public synchronized void releaseOutputReservation(String reservationId) {
        if (reservationId == null) {
            return;
        }
        Integer reserved = outputReservations.remove(reservationId);
        if (reserved != null) {
            outputTokensReserved -= reserved;
        }
    }

    /** 返回指定请求的预留额度；未知标识返回零，避免迟到响应重复结算。 */
    public synchronized int reservedOutputTokens(String reservationId) {
        return reservationId == null ? 0 : outputReservations.getOrDefault(reservationId, 0);
    }

    /**
     * 设置本轮模型输入预算，并记录组装阶段的完整估算。
     */
    public synchronized void initializeContextBudget(int budget, int estimatedTokens) {
        contextBudget = Math.max(1, budget);
        contextTokensUsed = Math.max(0, estimatedTokens);
        contextTokensPeak = contextTokensUsed;
    }

    /**
     * 在每次真实模型请求前检查包含工具结果的完整 Prompt 估算。
     */
    public synchronized boolean checkContextBudget(int estimatedTokens) {
        contextTokensUsed = Math.max(0, estimatedTokens);
        contextTokensPeak = Math.max(contextTokensPeak, contextTokensUsed);
        if (contextTokensUsed > contextBudget) {
            markStopped(AgentExecutionStopReasonEnum.CONTEXT_BUDGET_EXCEEDED);
            return false;
        }
        return true;
    }

    /** 设置 Advisor 当前正在发送的固定上下文视图版本。 */
    public synchronized void setContextViewKey(String viewKey) {
        contextViewKey = viewKey == null || viewKey.isBlank() ? null : viewKey;
    }

    public synchronized String contextViewKey() {
        return contextViewKey;
    }

    /** 记录 Advisor 实际发送的完整 Prompt 估算，供 Runtime 比较恢复前后的同一渲染结果。 */
    public synchronized void recordPromptMeasurement(String viewKey, int estimatedTokens) {
        promptMeasurement = new PromptMeasurement(viewKey, estimatedTokens);
        if (viewKey != null && !viewKey.isBlank()) {
            ContextCompactionAttempt attempt = contextCompactionAttempts.get(viewKey);
            if (attempt != null && attempt.afterEstimatedTokens() <= 0 && estimatedTokens > 0) {
                contextCompactionAttempts.put(viewKey, new ContextCompactionAttempt(
                        viewKey, attempt.beforeEstimatedTokens(), estimatedTokens,
                        estimatedTokens < attempt.beforeEstimatedTokens()));
            }
        }
    }

    public synchronized PromptMeasurement promptMeasurement() {
        return promptMeasurement;
    }

    /** 记录一个固定水位加视图版本的压缩候选，避免 Advisor 在同一候选上循环重入。 */
    public synchronized void markContextCompactionAttempted(String viewKey) {
        recordContextCompactionAttempt(viewKey, 0, 0);
    }

    /**
     * 保存候选的压缩结果。结果只用于本 Turn 的防重入和受控观测，不改变原始业务事实。
     */
    public synchronized void recordContextCompactionAttempt(String viewKey, int beforeEstimatedTokens,
                                                              int afterEstimatedTokens) {
        if (viewKey == null || viewKey.isBlank()) {
            return;
        }
        if (contextCompactionAttempts.size() >= 64 && !contextCompactionAttempts.containsKey(viewKey)) {
            String oldest = contextCompactionAttempts.keySet().iterator().next();
            contextCompactionAttempts.remove(oldest);
        }
        contextCompactionAttempts.put(viewKey, new ContextCompactionAttempt(
                viewKey, Math.max(0, beforeEstimatedTokens), Math.max(0, afterEstimatedTokens),
                afterEstimatedTokens > 0 && afterEstimatedTokens < beforeEstimatedTokens));
    }

    public synchronized Optional<ContextCompactionAttempt> contextCompactionAttempt(String viewKey) {
        return viewKey == null ? Optional.empty() : Optional.ofNullable(contextCompactionAttempts.get(viewKey));
    }

    public synchronized boolean contextCompactionAttempted(String viewKey) {
        return viewKey != null && contextCompactionAttempts.containsKey(viewKey);
    }

    /** 在供应商拒绝后固定被拒请求的完整 Prompt，下一次 Advisor 请求必须严格缩减后才能发送。 */
    public synchronized void beginContextOverflowRecovery(String viewKey, int estimatedTokens) {
        pendingOverflowRecoveryBaseline = new PromptMeasurement(viewKey, estimatedTokens);
    }

    /**
     * 校验恢复请求是否相对供应商实际拒绝的 Prompt 严格缩减并改变视图；成功时消耗共享重试额度。
     */
    public synchronized boolean validateContextOverflowRecovery(String viewKey, int estimatedTokens,
                                                                 int maxRetries) {
        if (pendingOverflowRecoveryBaseline == null) {
            return true;
        }
        PromptMeasurement baseline = pendingOverflowRecoveryBaseline;
        boolean reduced = !Objects.equals(baseline.viewKey(), viewKey)
                && estimatedTokens < baseline.estimatedTokens();
        if (!reduced || contextOverflowRetries >= Math.max(0, maxRetries)) {
            pendingOverflowRecoveryBaseline = null;
            return false;
        }
        contextOverflowRetries++;
        pendingOverflowRecoveryBaseline = null;
        return true;
    }

    public synchronized boolean contextOverflowRecoveryPending() {
        return pendingOverflowRecoveryBaseline != null;
    }

    /**
     * 新 Tool 批次已经推进了持久化事实水位，允许下一次模型请求针对新视图重新触发一次压缩。
     */
    public synchronized void resetContextCompactionAttempted() {
        contextCompactionAttempts.clear();
        contextViewKey = null;
        promptMeasurement = null;
        pendingOverflowRecoveryBaseline = null;
    }

    public synchronized int contextOverflowRetries() {
        return contextOverflowRetries;
    }

    public record PromptMeasurement(String viewKey, int estimatedTokens) {
        public PromptMeasurement {
            viewKey = viewKey == null || viewKey.isBlank() ? null : viewKey;
            estimatedTokens = Math.max(0, estimatedTokens);
        }
    }

    public record ContextCompactionAttempt(
            String viewKey,
            int beforeEstimatedTokens,
            int afterEstimatedTokens,
            boolean reduced
    ) {
    }

    public synchronized boolean outputBudgetExhausted() {
        return outputTokensUsed + outputTokensReserved >= maxOutputTokens;
    }

    public synchronized long outputTokensUsed() {
        return outputTokensUsed;
    }

    /** 返回本 Turn 的累计输出上限，供摘要和正常模型请求共同计算剩余额度。 */
    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public synchronized int contextBudget() {
        return contextBudget;
    }

    public synchronized int contextTokensUsed() {
        return contextTokensUsed;
    }

    public synchronized int contextTokensPeak() {
        return contextTokensPeak;
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

    /** 返回当前请求可等待的剩余时间，供摘要流和外部适配器取消阻塞等待。 */
    public Duration remainingTime() {
        Duration remaining = Duration.between(clock.instant(), deadline);
        return remaining.isNegative() || remaining.isZero() ? Duration.ZERO : remaining;
    }
}
