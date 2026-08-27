package cn.ethan.core.agent.workflow;

/**
 * 类型职责：描述 QuestionCard 回答 Turn 的预留、入队和消费状态。
 *
 * @author ethan
 * @date 2026-08-27
 */
public enum AgentQuestionCardAnswerEnqueueStatusEnum {
    AVAILABLE,
    RESERVED,
    ENQUEUED,
    CONSUMED;

    public boolean requiresAnswerTurn() {
        return this == RESERVED || this == ENQUEUED || this == CONSUMED;
    }
}
