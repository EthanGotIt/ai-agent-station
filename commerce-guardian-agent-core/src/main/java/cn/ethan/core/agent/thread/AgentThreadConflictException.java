package cn.ethan.core.agent.thread;

/**
 * Thread 运行状态冲突。
 *
 * @author ethan
 * @date 2026-08-19
 */
public final class AgentThreadConflictException extends RuntimeException {
    private final String code;

    public AgentThreadConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
