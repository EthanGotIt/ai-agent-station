package cn.ethan.core.agent.support;

import cn.ethan.core.agent.model.AgentMemoryCandidateModel;
import cn.ethan.core.agent.model.AgentMemoryExtractionInputModel;
import cn.ethan.core.agent.port.AgentMemoryExtractionProvider;
import cn.ethan.core.agent.service.AgentMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 会话记忆后台协调器：以单实例 best-effort debounce 合并相邻完成回合。
 *
 * @author ethan
 * @date 2026-08-10
 */
public final class AgentMemoryExtractionCoordinator implements AutoCloseable {

    private static final int MAX_BATCH_TURNS = 6;
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentMemoryExtractionCoordinator.class);

    private final Duration idleDelay;
    private final ScheduledExecutorService scheduler;
    private final Executor executor;
    private final AgentMemoryExtractionProvider provider;
    private final AgentMemoryService memories;
    private final Map<String, PendingBatch> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AgentMemoryExtractionCoordinator(
            Duration idleDelay,
            ScheduledExecutorService scheduler,
            Executor executor,
            AgentMemoryExtractionProvider provider,
            AgentMemoryService memories
    ) {
        if (idleDelay == null || idleDelay.isNegative() || idleDelay.isZero()) {
            throw new IllegalArgumentException("memory idle delay must be positive");
        }
        this.idleDelay = idleDelay;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.memories = Objects.requireNonNull(memories, "memories must not be null");
    }

    public void schedule(AgentMemoryExtractionInputModel input) {
        Objects.requireNonNull(input, "input must not be null");
        if (closed.get()) {
            return;
        }
        String key = input.userId() + '\u0000' + input.sessionId();
        AtomicReference<RuntimeException> rejection = new AtomicReference<>();
        pending.compute(key, (ignored, existing) -> {
            if (closed.get()) {
                return existing;
            }
            PendingBatch target = existing == null ? new PendingBatch() : existing;
            target.append(input);
            try {
                target.reschedule(key);
                return target;
            } catch (RuntimeException schedulingFailure) {
                // 新任务未创建时保留旧 future；首次调度失败则不把空批次写入映射。
                rejection.set(schedulingFailure);
                return existing;
            }
        });
        RuntimeException schedulingFailure = rejection.get();
        if (schedulingFailure == null) {
            memories.recordExtractionOutcome("scheduled");
            return;
        }
        memories.recordExtractionOutcome("rejected");
        LOGGER.warn("记忆提取调度被拒绝，sessionKeyHash={}，exception={}",
                Integer.toHexString(key.hashCode()), schedulingFailure.getClass().getSimpleName());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (!pending.isEmpty()) {
            memories.recordExtractionOutcome("cancelled");
        }
        pending.values().forEach(PendingBatch::cancel);
        pending.clear();
    }

    private void extract(String key, PendingBatch batch) {
        // 先从映射中原子摘除，随后到达的回合会建立下一批，避免 drain 后被旧任务误删。
        if (!pending.remove(key, batch)) {
            return;
        }
        List<AgentMemoryExtractionInputModel> inputs = batch.drain();
        if (inputs.isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    List<AgentMemoryCandidateModel> candidates = provider.extract(inputs);
                    memories.persistAutomatic(inputs, candidates);
                } catch (RuntimeException failure) {
                    memories.recordExtractionOutcome("failed");
                    // 记忆为辅助召回层；模型或存储异常不得改变已经完成的用户请求。
                    LOGGER.warn("记忆提取批次失败，sessionKeyHash={}，exception={}",
                            Integer.toHexString(key.hashCode()), failure.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException failure) {
            memories.recordExtractionOutcome("rejected");
            // 有界执行器满载时直接跳过本批；后续会话仍可重新生成记忆。
            LOGGER.warn("记忆提取队列拒绝批次，sessionKeyHash={}，exception={}",
                    Integer.toHexString(key.hashCode()), failure.getClass().getSimpleName());
        }
    }

    private final class PendingBatch {

        private final List<AgentMemoryExtractionInputModel> inputs = new ArrayList<>();
        private ScheduledFuture<?> future;

        private synchronized void append(AgentMemoryExtractionInputModel input) {
            inputs.removeIf(item -> item.requestId().equals(input.requestId()));
            inputs.add(input);
            while (inputs.size() > MAX_BATCH_TURNS) {
                inputs.remove(0);
            }
        }

        private synchronized void reschedule(String key) {
            ScheduledFuture<?> nextFuture = scheduler.schedule(
                    () -> extract(key, this), idleDelay.toMillis(), TimeUnit.MILLISECONDS
            );
            ScheduledFuture<?> previous = future;
            future = nextFuture;
            if (previous != null) {
                previous.cancel(false);
            }
        }

        private synchronized List<AgentMemoryExtractionInputModel> drain() {
            future = null;
            List<AgentMemoryExtractionInputModel> result = List.copyOf(inputs);
            inputs.clear();
            return result;
        }

        private synchronized void cancel() {
            if (future != null) {
                future.cancel(false);
            }
            inputs.clear();
        }
    }
}
