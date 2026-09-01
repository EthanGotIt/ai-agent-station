package cn.ethan.core.agent.workflow;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 类型职责：持久化 Agent 与 Workflow 共用的 QuestionCard，并以版本 CAS 管理回答入队。
 *
 * @author ethan
 * @date 2026-08-27
 */
public interface AgentQuestionCardStore {

    Optional<AgentQuestionCardModel> find(String userId, String questionId);

    Optional<AgentQuestionCardModel> findOpen(String userId, String threadId);

    void create(AgentQuestionCardModel question);

    default void save(AgentQuestionCardModel question) {
        create(question);
    }

    OptionalLong reserveAnswerTurn(String userId, String questionId, long expectedVersion, String answerTurnId);

    OptionalLong markAnswerTurnEnqueued(String userId, String questionId, long expectedVersion, String answerTurnId);

    boolean releaseAnswerTurn(String userId, String questionId, long expectedVersion, String answerTurnId);

    boolean closeAnswerTurn(String userId, String questionId, long expectedVersion,
                            String answerTurnId, AgentQuestionCardStatusEnum terminalStatus, Instant answeredAt);
}
