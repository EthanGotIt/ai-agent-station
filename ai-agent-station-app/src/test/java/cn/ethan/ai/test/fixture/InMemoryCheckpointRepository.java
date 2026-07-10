package cn.ethan.ai.test.fixture;

import cn.ethan.ai.domain.agent.model.Checkpoint;
import cn.ethan.ai.domain.agent.port.driven.ICheckpointRepository;
import cn.ethan.ai.types.common.id.CaseId;
import cn.ethan.ai.types.common.id.CheckpointId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版 Checkpoint 仓储，供单元测试使用。
 *
 * <p>按插入顺序保存，便于验证过程快照与 Turn 边界。</p>
 */
public final class InMemoryCheckpointRepository implements ICheckpointRepository {

    private final Map<String, Checkpoint> checkpoints = new ConcurrentHashMap<>();
    private final List<String> insertionOrder = new ArrayList<>();

    @Override
    public synchronized void save(Checkpoint checkpoint) {
        checkpoints.put(checkpoint.checkpointId().value(), checkpoint);
        insertionOrder.add(checkpoint.checkpointId().value());
    }

    @Override
    public Optional<Checkpoint> findById(CheckpointId checkpointId) {
        return Optional.ofNullable(checkpoints.get(checkpointId.value()));
    }

    public Map<String, Checkpoint> all() {
        return Map.copyOf(checkpoints);
    }

    public List<Checkpoint> findByCaseId(CaseId caseId) {
        synchronized (this) {
            return insertionOrder.stream()
                    .map(checkpoints::get)
                    .filter(cp -> cp != null && cp.caseId().equals(caseId))
                    .collect(Collectors.toList());
        }
    }
}
