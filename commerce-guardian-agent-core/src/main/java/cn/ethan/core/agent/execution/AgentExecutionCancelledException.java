package cn.ethan.core.agent.execution;

import java.io.Serial;

/**
 * 类型职责：标识协作取消或截止时间触发的执行中断，避免把它误记为模型故障。
 *
 * @author ethan
 * @date 2026-08-20
 */
public final class AgentExecutionCancelledException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AgentExecutionCancelledException(String message) {
        super(message);
    }
}
