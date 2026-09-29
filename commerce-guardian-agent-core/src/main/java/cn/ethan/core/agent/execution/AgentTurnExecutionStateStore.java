package cn.ethan.core.agent.execution;

import java.util.Optional;

/**
 * 类型职责：持久化并以版本号保护 Turn 的暂停恢复位置。
 *
 * @author ethan
 * @date 2026-09-29
 */
public interface AgentTurnExecutionStateStore {

    Optional<AgentTurnExecutionStateModel> find(String turnId);

    void create(AgentTurnExecutionStateModel state);

    boolean update(AgentTurnExecutionStateModel expected, AgentTurnExecutionStateModel next);
}
