package cn.ethan.core.agent.execution;

/**
 * 类型职责：以本地事务原子完成 QuestionCard 回答预留、回答 Turn 和首个 Item 持久化。
 *
 * @author ethan
 * @date 2026-08-27
 */
public interface AgentQuestionAnswerAdmission {

    AgentQuestionAnswerAdmissionResult admit(AgentQuestionAnswerAdmissionCommand command);
}
