package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentTurnModel;

/**
 * 类型职责：将已经在本地事务中持久化的系统 Turn 放回 Thread FIFO 队列。
 *
 * <p>持久化与入队分离，允许调用方在事务提交后再触发内存调度；进程崩溃时由恢复扫描兜底。</p>
 *
 * @author ethan
 * @date 2026-08-26
 */
public interface AgentTurnQueue {

    /** 将已持久化且仍为 QUEUED 的 Turn 安全加入运行时队列。 */
    void enqueuePersisted(AgentTurnModel turn);
}
