package cn.ethan.core.agent.support;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消令牌：在模型调用、节点执行和重试边界提供协作式取消能力。
 *
 * @author ethan
 * @date 2026-08-05
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancellationException("request cancelled");
        }
    }
}
