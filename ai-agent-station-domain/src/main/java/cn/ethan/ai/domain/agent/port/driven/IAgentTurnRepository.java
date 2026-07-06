package cn.ethan.ai.domain.agent.port.driven;

import cn.ethan.ai.domain.agent.model.valobj.AgentTurnRecord;

/**
 * Turn 运行态仓储。
 *
 * <p>Run 已合并进 Turn，一次外部交互即一次执行尝试。</p>
 */
public interface IAgentTurnRepository {

    void createTurn(AgentTurnRecord record);

    void completeTurn(AgentTurnRecord record);

    int nextAttemptNo(String caseId);
}
