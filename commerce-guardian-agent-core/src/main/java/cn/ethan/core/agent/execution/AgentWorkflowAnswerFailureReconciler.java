package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentItemModel;

import java.time.Instant;

/**
 * 类型职责：原子释放失败回答绑定并把对应 Turn 收敛到终态，供失败重试和启动对账复用。
 *
 * @author ethan
 * @date 2026-08-21
 */
public interface AgentWorkflowAnswerFailureReconciler {

    /**
     * 释放仍绑定该 Turn 的 OPEN Question，并在同一事务写入 Turn 终态。
     *
     * @return true 表示 Question 已释放或已被安全推进，且 Turn 终态已持久化
     */
    boolean reconcile(
            AgentTurnModel turn,
            AgentTurnStatusEnum terminalStatus,
            String errorCode,
            Instant finishedAt
    );

    /**
     * 在同一持久化边界内释放回答并返回可重试 Question Item，供实时事件发布复用。
     * 未提供投影的旧适配器仍可通过默认实现参与对账。
     */
    default ReconciliationResult reconcileWithProjection(
            AgentTurnModel turn,
            AgentTurnStatusEnum terminalStatus,
            String errorCode,
            Instant finishedAt
    ) {
        return new ReconciliationResult(reconcile(turn, terminalStatus, errorCode, finishedAt), null);
    }

    /** 对账提交后可安全发布的重试 Question Item。 */
    record ReconciliationResult(boolean reconciled, AgentItemModel retryQuestionItem) {
    }
}
