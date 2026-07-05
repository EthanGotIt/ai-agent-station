package cn.ethan.ai.domain.agent.port.driven;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesAgentStateSnapshot;

import java.util.Map;
import java.util.Optional;

/**
 * 售后Agent状态机端口。
 */
public interface IAfterSalesStateMachine {

    AfterSalesAgentState execute(Map<String, Object> input, String threadId);

    AfterSalesAgentState resume(Map<String, Object> update, String threadId, String checkpointId);

    Optional<AfterSalesAgentStateSnapshot> currentSnapshot(String threadId);
}
