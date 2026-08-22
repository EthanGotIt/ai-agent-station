package cn.ethan.core.agent.thread;

/**
 * 类型职责：在 Thread 进入回收站前检查仍可能产生业务副作用的活动事实。
 *
 * @author ethan
 * @date 2026-08-23
 */
@FunctionalInterface
public interface AgentThreadArchiveGuard {

    /**
     * 若 Thread 仍有排队/执行 Turn、开放 Question 或未完成外部动作，应抛出冲突异常。
     */
    void ensureCanArchive(String userId, String threadId);
}
