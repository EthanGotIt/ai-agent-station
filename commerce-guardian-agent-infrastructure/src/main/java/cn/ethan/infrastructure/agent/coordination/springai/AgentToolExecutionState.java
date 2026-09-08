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

    /** 标记一次模型响应中的完整 Tool Call 批次，便于压缩只在批次边界发生。 */
    default void beginToolBatch(String batchId) {
    }

    default void endToolBatch() {
    }

    String boundToolResult(String value);

    /** 绑定当前真实模型请求的输出预留，保证响应按请求标识结算一次。 */
    void bindModelOutputReservation(String reservationId);

    void settleModelOutput(ChatResponse response);

    /** 按请求上下文中的标识结算，避免迟到响应误扣当前请求。 */
    default void settleModelOutput(ChatResponse response, String reservationId) {
        settleModelOutput(response);
    }
}
