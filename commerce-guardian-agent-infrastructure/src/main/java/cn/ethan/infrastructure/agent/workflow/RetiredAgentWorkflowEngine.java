package cn.ethan.infrastructure.agent.workflow;

import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 类型职责：在旧编排排空后拒绝遗留 Run 的执行请求，同时保留明确、可观测的错误边界。
 *
 * @author ethan
 * @date 2026-09-28
 */
@Component("retiredAgentWorkflowEngine")
public final class RetiredAgentWorkflowEngine implements AgentWorkflowEngine {

    private static final String CODE = "WORKFLOW_COMPATIBILITY_REQUIRED";
    private static final String MESSAGE = "该历史 Workflow 编排已退出生产执行路径，请使用兼容版本完成排空或明确取消";

    @Override
    public StartResult start(AgentThreadModel thread, AgentTurnModel turn,
                             String operation, Map<String, String> arguments) {
        throw retired();
    }

    @Override
    public ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn,
                               Map<String, String> answers) {
        throw retired();
    }

    private AgentThreadConflictException retired() {
        return new AgentThreadConflictException(CODE, MESSAGE);
    }
}
