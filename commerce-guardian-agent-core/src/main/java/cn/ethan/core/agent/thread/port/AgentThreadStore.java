package cn.ethan.core.agent.thread.port;

import cn.ethan.core.agent.thread.model.AgentContextSnapshotModel;
import cn.ethan.core.agent.thread.model.AgentItemModel;
import cn.ethan.core.agent.thread.model.AgentQuestionModel;
import cn.ethan.core.agent.thread.model.AgentThreadModel;
import cn.ethan.core.agent.thread.model.AgentTurnModel;

import java.util.List;
import java.util.Optional;

/**
 * Thread 事实存储端口。
 *
 * @author ethan
 * @date 2026-08-19
 */
public interface AgentThreadStore {

    void createThread(AgentThreadModel thread);

    Optional<AgentThreadModel> findThread(String userId, String threadId);

    List<AgentThreadModel> listThreads(String userId);

    void updateThread(AgentThreadModel thread);

    Optional<AgentTurnModel> findTurnByRequest(String userId, String clientRequestId);

    void createTurn(AgentTurnModel turn);

    void updateTurn(AgentTurnModel turn);

    List<AgentTurnModel> listTurns(String userId, String threadId);

    List<AgentTurnModel> listRecoverableTurns();

    long appendItem(AgentItemModel item);

    List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit);

    Optional<AgentQuestionModel> findOpenQuestion(String userId, String threadId);

    Optional<AgentQuestionModel> findOpenQuestionByRun(String userId, String runId);

    void saveQuestion(AgentQuestionModel question);

    void answerQuestion(AgentQuestionModel question);

    Optional<AgentContextSnapshotModel> findLatestSnapshot(String userId, String threadId);

    void saveSnapshot(AgentContextSnapshotModel snapshot);
}
