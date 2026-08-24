package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.coordination.AgentOrderActionCoordinator;
import cn.ethan.core.agent.coordination.AgentTurnCoordinator;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnInputKindEnum;
import cn.ethan.core.agent.thread.AgentTurnModel;

import java.util.List;
import java.util.Map;

/**
 * 类型职责：将普通消息、Workflow 回答和订单卡片动作分派到各自的执行端口。
 *
 * <p>Runtime 只负责排队、超时、取消和事实收口；确定性动作不会落入模型协调器。</p>
 *
 * @author ethan
 * @date 2026-08-24
 */
public final class AgentTurnExecutionRouter {

    private final AgentTurnCoordinator conversationCoordinator;
    private final AgentOrderActionCoordinator orderActionCoordinator;

    public AgentTurnExecutionRouter(
            AgentTurnCoordinator conversationCoordinator,
            AgentOrderActionCoordinator orderActionCoordinator
    ) {
        this.conversationCoordinator = conversationCoordinator;
        this.orderActionCoordinator = orderActionCoordinator;
    }

    public AgentTurnCoordinator.AgentCoordinatorResult route(
            AgentThreadModel thread,
            AgentTurnModel turn,
            List<AgentItemModel> context,
            Map<String, String> answers,
            AgentExecutionContext executionContext
    ) {
        executionContext.checkActive();
        if (turn.inputKind() == AgentTurnInputKindEnum.ORDER_ACTION
                && turn.orderActionInput() != null) {
            if (orderActionCoordinator == null) {
                throw new IllegalStateException("订单动作协调器未装配");
            }
            return orderActionCoordinator.run(thread, turn, context,
                    turn.orderActionInput(), executionContext);
        }
        AgentTurnCoordinator.AgentCoordinatorResult result = conversationCoordinator.run(
                thread, turn, context, answers, executionContext);
        executionContext.checkActive();
        return result;
    }
}
