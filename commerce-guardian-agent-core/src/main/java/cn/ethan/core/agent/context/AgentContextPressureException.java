package cn.ethan.core.agent.context;

/**
 * 类型职责：通知 Runtime 在真实模型请求前先完成一次上下文压缩。
 *
 * @author ethan
 * @date 2026-09-05
 */
public final class AgentContextPressureException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final int estimatedTokens;
    private final String viewKey;

    public AgentContextPressureException(String message) {
        this(message, 0, null);
    }

    public AgentContextPressureException(String message, int estimatedTokens, String viewKey) {
        super(message);
        this.estimatedTokens = Math.max(0, estimatedTokens);
        this.viewKey = viewKey;
    }

    public int estimatedTokens() {
        return estimatedTokens;
    }

    public String viewKey() {
        return viewKey;
    }
}
