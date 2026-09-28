package cn.ethan.core.agent.workflow;

/**
 * 类型职责：标识 WorkflowRun 使用的确定性编排路径，保证恢复不会跨版本回退。
 *
 * @author ethan
 * @date 2026-09-06
 */
public enum AgentWorkflowOrchestrationVersionEnum {
    LEGACY_V1,
    EXPEDITE_GRAPH_V1,
    EXPEDITE_GRAPH_V2,
    EXPEDITE_JAVA_V1
}
