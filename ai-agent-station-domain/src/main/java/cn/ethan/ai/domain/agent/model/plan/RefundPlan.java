package cn.ethan.ai.domain.agent.model.plan;

import java.util.List;

/**
 * 退款信息收集计划。
 *
 * <p>由 {@link cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent} 生成，
 * 用于描述当前已收集信息与达成评估条件之间的差距。</p>
 *
 * @param readyToEvaluate 是否已具备评估退款资格的全部信息
 * @param steps           下一步执行步骤
 * @param checklist       关键字段检查清单
 */
public record RefundPlan(
        int schemaVersion,
        boolean readyToEvaluate,
        List<EvidenceGap> evidenceGaps,
        List<PlannedStep> steps,
        List<ChecklistItem> checklist
) {

    public RefundPlan(boolean readyToEvaluate, List<PlannedStep> steps, List<ChecklistItem> checklist) {
        this(1, readyToEvaluate, List.of(), steps, checklist);
    }
}
