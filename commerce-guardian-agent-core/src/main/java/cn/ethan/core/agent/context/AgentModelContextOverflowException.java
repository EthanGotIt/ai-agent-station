package cn.ethan.core.agent.context;

/**
 * 类型职责：表示模型供应商明确拒绝了超出上下文窗口的请求。
 *
 * @author ethan
 * @date 2026-09-05
 */
public final class AgentModelContextOverflowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AgentModelContextOverflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
