package cn.ethan.core.agent.exception;

/**
 * 会话记忆不存在异常：对非归属用户和会话统一隐藏条目存在性。
 *
 * @author ethan
 * @date 2026-08-10
 */
public final class AgentMemoryNotFoundException extends RuntimeException {

    public AgentMemoryNotFoundException(String entryId) {
        super("memory entry not found: " + entryId);
    }
}
