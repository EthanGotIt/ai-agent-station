package cn.ethan.core.agent.context;

import cn.ethan.core.agent.execution.AgentExecutionContext;

/**
 * 类型职责：通过模型生成上下文派生摘要，不执行工具或外部写操作。
 *
 * @author ethan
 * @date 2026-09-05
 */
@FunctionalInterface
public interface AgentContextSummaryGateway {

    String summarize(AgentContextSummaryRequest request, AgentExecutionContext executionContext);
}
