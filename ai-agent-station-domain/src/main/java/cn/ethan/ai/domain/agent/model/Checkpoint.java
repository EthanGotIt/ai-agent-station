package cn.ethan.ai.domain.agent.model;

import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.types.common.id.CaseId;
import cn.ethan.ai.types.common.id.CheckpointId;
import cn.ethan.ai.types.common.id.StepId;
import cn.ethan.ai.types.common.id.TurnId;

import java.time.LocalDateTime;

/**
 * 状态机 Checkpoint。
 *
 * <p>在 Plan-and-Execute 循环的每次有意义状态变更后持久化，
 * 用于进程重启、消息重投或并发恢复时重建状态机。</p>
 *
 * @param checkpointId Checkpoint 唯一标识
 * @param caseId       所属售后 Case
 * @param turnId       触发本次 Checkpoint 的 Turn
 * @param stepId       触发 Checkpoint 的步骤 ID；为空表示 Turn 边界或终态
 * @param ssmState     Spring State Machine 状态名
 * @param state        完整的 Agent 业务状态
 * @param stage        业务 Stage
 * @param createdAt    创建时间
 */
public record Checkpoint(
        CheckpointId checkpointId,
        CaseId caseId,
        TurnId turnId,
        StepId stepId,
        String ssmState,
        AfterSalesAgentState state,
        AfterSalesStage stage,
        LocalDateTime createdAt
) {
}
