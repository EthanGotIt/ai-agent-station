package cn.ethan.core.agent.exception;

/**
 * 会话记忆版本冲突异常：防止陈旧页面覆盖较新的人工编辑。
 *
 * @author ethan
 * @date 2026-08-10
 */
public final class AgentMemoryConflictException extends RuntimeException {

    public AgentMemoryConflictException(String entryId) {
        super("memory entry version has changed: " + entryId);
    }
}
