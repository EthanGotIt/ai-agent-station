package cn.ethan.core.agent.thread;

import java.util.List;
import java.util.Optional;

/**
 * 类型职责：持久化 Turn 状态和请求幂等关系，不负责 Thread 元数据或 Item 内容。
 *
 * @author ethan
 * @date 2026-08-20
 */
public interface AgentTurnStore {

    Optional<AgentTurnModel> findTurn(String userId, String turnId);

    Optional<AgentTurnModel> findTurnByRequest(String userId, String clientRequestId);

    void createTurn(AgentTurnModel turn);

    void updateTurn(AgentTurnModel turn);

    List<AgentTurnModel> listRecoverableTurns();
}
