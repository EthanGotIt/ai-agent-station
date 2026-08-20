package cn.ethan.core.agent.workflow;

/**
 * 类型职责：限制 QuestionCard 的开放与关闭状态。
 *
 * @author ethan
 * @date 2026-08-20
 */
public enum AgentWorkflowQuestionStatusEnum {
    OPEN,
    ANSWERED;

    /**
     * 类型职责：描述 QuestionCard 回答 Turn 的预留、入队和消费状态。
     *
     * @author ethan
     * @date 2026-08-20
     */
    public enum AnswerEnqueueStatusEnum {
        AVAILABLE,
        RESERVED,
        ENQUEUED,
        CONSUMED;

        /**
         * 判断该状态是否必须绑定回答 Turn。
         *
         * @return RESERVED 或 ENQUEUED 状态是否需要 Turn 标识
         */
        public boolean requiresAnswerTurn() {
            return this == RESERVED || this == ENQUEUED;
        }
    }
}
