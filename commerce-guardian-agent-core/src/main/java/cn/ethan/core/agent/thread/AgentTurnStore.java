package cn.ethan.core.agent.thread;

import java.util.List;
import java.util.Optional;

/**
 * 类型职责：持久化 Turn 状态和请求幂等关系，并为首次事实提供原子创建边界。
 *
 * @author ethan
 * @date 2026-08-20
 */
public interface AgentTurnStore {

    Optional<AgentTurnModel> findTurn(String userId, String turnId);

    Optional<AgentTurnModel> findTurnByRequest(String userId, String clientRequestId);

    void createTurn(AgentTurnModel turn);

    /**
     * 创建 Turn 并尝试把首个事实写入同一持久化事务。
     *
     * <p>默认实现保留内存适配器的兼容性；数据库适配器应返回已分配的正数 Sequence，
     * 由同一事务完成 Turn、Thread 序号和首个 Item 的写入。</p>
     */
    default long createTurnWithInitialItem(AgentTurnModel turn, AgentItemModel initialItem) {
        createTurn(turn);
        return 0L;
    }

    /**
     * 以旧模型版本执行 Turn CAS；失败时调用方不得追加基于旧状态的事实。
     * 已进入终态的 Turn 不允许再次转换。
     *
     * @return 是否成功推进一个版本
     */
    boolean updateTurn(AgentTurnModel expected, AgentTurnModel next);

    List<AgentTurnModel> listRecoverableTurns();

    /** 返回终态已写入但仍可能绑定 OPEN+ENQUEUED Question 的历史回答 Turn。 */
    default List<AgentTurnModel> listWorkflowAnswerReconciliationCandidates() {
        return List.of();
    }
}
