package cn.ethan.core.agent.workflow;

import java.time.Instant;
import java.util.OptionalLong;
import java.util.Optional;

/**
 * 类型职责：持久化 QuestionCard 的开放状态和乐观版本，确保一个 Thread 只有一个待答问题。
 *
 * @author ethan
 * @date 2026-08-20
 */
public interface AgentWorkflowQuestionStore {

    Optional<AgentWorkflowQuestionModel> findOpenQuestion(String userId, String threadId);

    Optional<AgentWorkflowQuestionModel> findOpenQuestionByRun(String userId, String runId);

    void saveQuestion(AgentWorkflowQuestionModel question);

    /**
     * 按 questionId + expectedVersion 预留唯一回答 Turn；失败表示版本已推进或已有回答在处理。
     */
    OptionalLong reserveAnswerTurn(String userId, String questionId, long expectedVersion, String answerTurnId);

    /** 标记同一预留回答 Turn 已写入 FIFO。 */
    OptionalLong markAnswerTurnEnqueued(String userId, String questionId, long expectedVersion, String answerTurnId);

    /** 取消或超时释放同一回答 Turn，并以版本推进阻断旧回答。 */
    boolean releaseAnswerTurn(String userId, String questionId, long expectedVersion, String answerTurnId);

    /** 仅允许已入队的同一回答 Turn 关闭 QuestionCard。 */
    boolean closeAnswerTurn(String userId, String questionId, long expectedVersion,
                            String answerTurnId, Instant answeredAt);
}
