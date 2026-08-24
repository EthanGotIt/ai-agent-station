package cn.ethan.core.agent.coordination;

import cn.ethan.core.agent.execution.AgentExecutionContext;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;

import java.util.List;

/**
 * 订单动作执行端口：读动作直接产出结构化事实，写动作只启动确定性 Workflow。
 *
 * @author ethan
 * @date 2026-08-24
 */
public interface AgentOrderActionCoordinator {

    AgentTurnCoordinator.AgentCoordinatorResult run(
            AgentThreadModel thread,
            AgentTurnModel turn,
            List<AgentItemModel> context,
            AgentOrderActionInput input,
            AgentExecutionContext executionContext
    );
}
