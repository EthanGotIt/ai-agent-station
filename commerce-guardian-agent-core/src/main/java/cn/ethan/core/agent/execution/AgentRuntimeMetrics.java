package cn.ethan.core.agent.execution;

import java.time.Duration;

/**
 * 类型职责：提供低基数运行时观测端口，Core 不依赖具体指标供应商。
 *
 * @author ethan
 * @date 2026-08-20
 */
public interface AgentRuntimeMetrics {

    void observeQueueWait(Duration duration);

    void observeTurn(Duration duration, String status);

    void observeContext(int estimatedTokens, boolean compressed, boolean degraded);

    /** 记录每次模型请求的输出预留和实际结算，维度保持低基数。 */
    default void observeOutputSettlement(int reservedTokens, int chargedTokens, boolean conservative) {
    }

    /** 记录压缩前后估算和裁剪数量；不携带 Thread、订单或摘要正文等高基数信息。 */
    default void observeContextCompaction(
            int beforeEstimatedTokens, int afterEstimatedTokens, int prunedToolResults, boolean summaryApplied
    ) {
    }

    void observeFailure(String category);

    default void observeTool(Duration duration, String status) {
    }

    default void observeWorkflowWait(Duration duration) {
    }

    default void observeWorkerRetry() {
    }

    default void observeLeaseTakeover() {
    }

    /** 记录 Workflow 编排版本和稳定生命周期事件，不携带 Thread、Run 或订单标签。 */
    default void observeWorkflow(String orchestrationVersion, String event) {
    }

    /** 记录事实重新核验结果，维度保持为有限枚举。 */
    default void observeWorkflowFacts(String orchestrationVersion, String result) {
    }

    /** 记录命令新建或幂等去重结果。 */
    default void observeWorkflowCommand(String orchestrationVersion, boolean deduplicated) {
    }

    /** 记录 Worker 结算结果。 */
    default void observeWorkflowWorker(String orchestrationVersion, String result) {
    }

    /** 记录 Workflow 恢复和技术快照重建结果，不携带 Thread、Run 或订单标签。 */
    default void observeWorkflowRecovery(String orchestrationVersion, String result) {
    }

    static AgentRuntimeMetrics noop() {
        return new AgentRuntimeMetrics() {
            @Override public void observeQueueWait(Duration duration) { }
            @Override public void observeTurn(Duration duration, String status) { }
            @Override public void observeContext(int estimatedTokens, boolean compressed, boolean degraded) { }
            @Override public void observeFailure(String category) { }
        };
    }
}
