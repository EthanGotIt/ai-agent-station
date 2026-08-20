package cn.ethan.core.agent.execution;

/**
 * 类型职责：原子完成 Workflow 回答预留、Turn 首事实持久化和 ENQUEUED 标记。
 *
 * @author ethan
 * @date 2026-08-21
 */
public interface AgentWorkflowAnswerAdmission {

    AgentWorkflowAnswerAdmissionResult admit(AgentWorkflowAnswerAdmissionCommand command);
}
