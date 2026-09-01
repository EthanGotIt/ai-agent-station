package cn.ethan.core.agent.thread;

import java.io.Serial;

/**
 * Thread 不存在或不属于当前用户。
 *
 * @author ethan
 * @date 2026-08-19
 */
public final class AgentThreadNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AgentThreadNotFoundException(String threadId) {
        super("Thread 不存在或不属于当前用户：" + threadId);
    }
}
