package cn.ethan.core.agent.workflow;

/**
 * 类型职责：声明 QuestionCard 回答后应恢复 Agent 对话还是固定 Workflow。
 *
 * @author ethan
 * @date 2026-08-27
 */
public enum AgentQuestionCardResumeTargetEnum {
    AGENT,
    WORKFLOW
}
