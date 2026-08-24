package cn.ethan.core.agent.workflow;

/**
 * 类型职责：区分 QuestionCard 的正常提交与无副作用取消。
 *
 * @author ethan
 * @date 2026-08-24
 */
public enum AgentWorkflowAnswerActionEnum {
    SUBMIT,
    CANCEL
}
