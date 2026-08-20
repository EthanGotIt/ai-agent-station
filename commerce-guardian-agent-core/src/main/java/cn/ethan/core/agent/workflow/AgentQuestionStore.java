package cn.ethan.core.agent.workflow;

import java.util.Optional;

/**
 * 类型职责：持久化 QuestionCard 的开放状态和乐观版本，确保一个 Thread 只有一个待答问题。
 *
 * @author ethan
 * @date 2026-08-20
 */
public interface AgentQuestionStore {

    Optional<AgentQuestionModel> findOpenQuestion(String userId, String threadId);

    Optional<AgentQuestionModel> findOpenQuestionByRun(String userId, String runId);

    void saveQuestion(AgentQuestionModel question);

    void answerQuestion(AgentQuestionModel question);
}
