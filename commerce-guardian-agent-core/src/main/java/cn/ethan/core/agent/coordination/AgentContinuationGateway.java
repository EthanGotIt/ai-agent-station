package cn.ethan.core.agent.coordination;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentTurnModel;

import java.util.List;

/**
 * 类型职责：统一由外部动作结果触发 Agent Continuation 的 admission、幂等和轮次边界。
 *
 * <p>调用方只提交已持久化的外部动作状态和结果 Item；根 Turn、父 Turn、WorkflowRun、
 * 命令、状态、Sequence 和 cycle 的组合键由实现统一计算，避免 Workflow 与 Worker 各自生成续跑事实。</p>
 *
 * @author ethan
 * @date 2026-08-27
 */
public interface AgentContinuationGateway {

    /**
     * 为已提交的外部动作结果创建或复用唯一的 Continuation Turn。
     *
     * @param command 已完成或要求人工重试的外部动作命令
     * @param triggerItem 与命令结果绑定的、已经分配 Sequence 的 Item
     * @return 新建、幂等复用、达到轮次上限或未满足触发条件的 admission 结果
     */
    AdmissionResult admit(ExternalActionCommandModel command, AgentItemModel triggerItem);

    /**
     * Continuation admission 的持久化结果；items 已由实现写入业务 Item Store，供调用方发布 SSE。
     */
    record AdmissionResult(
            AgentTurnModel turn,
            List<AgentItemModel> items,
            boolean newlyAdmitted,
            boolean stopLimit,
            int cycleNo
    ) {

        public AdmissionResult {
            items = items == null ? List.of() : List.copyOf(items);
            if (cycleNo < 0) {
                throw new IllegalArgumentException("Continuation cycleNo 不能为负数");
            }
            if (newlyAdmitted && turn == null) {
                throw new IllegalArgumentException("新建 Continuation 必须返回 Turn");
            }
            if (stopLimit && turn != null) {
                throw new IllegalArgumentException("STOP_LIMIT 结果不能同时返回新 Turn");
            }
        }

        public static AdmissionResult none() {
            return new AdmissionResult(null, List.of(), false, false, 0);
        }

        public static AdmissionResult stopLimit(List<AgentItemModel> items, int cycleNo) {
            return new AdmissionResult(null, items, false, true, cycleNo);
        }
    }
}
