package cn.ethan.core.agent.execution;

import java.io.Serial;

/**
 * 类型职责：在真实模型请求前报告已知的 Turn 资源停止原因，不把它误判为供应商故障。
 *
 * @author ethan
 * @date 2026-09-04
 */
public final class AgentExecutionLimitException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final AgentExecutionStopReasonEnum reason;

    public AgentExecutionLimitException(AgentExecutionStopReasonEnum reason) {
        super(reason == null ? "Agent Turn 资源预算已耗尽" : reason.name());
        this.reason = reason;
    }

    public AgentExecutionStopReasonEnum reason() {
        return reason;
    }
}
