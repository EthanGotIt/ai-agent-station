package cn.ethan.infrastructure.agent.coordination.springai;

import cn.ethan.core.agent.execution.AgentExecutionContext;
import cn.ethan.core.agent.execution.AgentExecutionStopReasonEnum;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 类型职责：在 Spring AI Advisor/Manager 与单个协调 Invocation 之间传递可变 Turn 状态。
 *
 * @author ethan
 * @date 2026-09-04
 */
interface AgentToolExecutionState {

    AgentExecutionContext executionContext();

    boolean terminal();

    boolean persistenceFailed();

    void markResourceStop(AgentExecutionStopReasonEnum reason);

    void markRepeatedToolFailure();

    String boundToolResult(String value);

    void settleModelOutput(ChatResponse response);
}
