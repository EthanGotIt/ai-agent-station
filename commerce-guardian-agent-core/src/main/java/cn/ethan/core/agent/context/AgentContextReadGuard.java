package cn.ethan.core.agent.context;

/**
 * 类型职责：在上下文分页读取边界传播 Turn 的取消和截止时间检查。
 *
 * @author ethan
 * @date 2026-09-04
 */
@FunctionalInterface
public interface AgentContextReadGuard {

    void check();
}
