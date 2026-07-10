package cn.ethan.ai.domain.agent.port.driven;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentStateSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesStateMachineResult;

import java.util.Map;
import java.util.Optional;

/**
 * 售后Agent状态机端口。
 */
public interface IAfterSalesStateMachine {

    AfterSalesStateMachineResult execute(Map<String, Object> input, String threadId);

    AfterSalesStateMachineResult resume(Map<String, Object> update, String threadId, String checkpointId);

    Optional<AfterSalesAgentStateSnapshot> currentSnapshot(String threadId);
}
