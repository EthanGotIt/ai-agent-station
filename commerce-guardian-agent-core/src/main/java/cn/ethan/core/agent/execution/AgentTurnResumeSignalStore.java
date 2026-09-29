package cn.ethan.core.agent.execution;

import java.util.Optional;

/**
 * 类型职责：以用户请求幂等键持久化并去重 Turn 恢复信号。
 *
 * @author ethan
 * @date 2026-09-29
 */
public interface AgentTurnResumeSignalStore {

    Optional<AgentTurnResumeSignalModel> findByRequest(String userId, String requestId);

    Optional<AgentTurnResumeSignalModel> findPending(String userId, String turnId);

    void create(AgentTurnResumeSignalModel signal);

    boolean markApplied(AgentTurnResumeSignalModel expected, AgentTurnResumeSignalModel next);
}
