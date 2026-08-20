package cn.ethan.core.agent.thread;

import java.io.Serial;

/**
 * Thread 运行状态冲突。
 *
 * @author ethan
 * @date 2026-08-19
 */
public final class AgentThreadConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;
    private final String code;

    public AgentThreadConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
