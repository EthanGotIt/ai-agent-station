package cn.ethan.core.agent.execution;

import java.io.Serial;

/**
 * 类型职责：标识模型或 Tool 流式执行超过 Turn 截止时间的中断。
 *
 * @author ethan
 * @date 2026-08-21
 */
public final class AgentExecutionTimeoutException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AgentExecutionTimeoutException(String message) {
        super(message);
    }

    public AgentExecutionTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
