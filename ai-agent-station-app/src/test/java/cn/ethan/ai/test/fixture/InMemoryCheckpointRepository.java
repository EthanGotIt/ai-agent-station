package cn.ethan.ai.test.fixture;

import cn.ethan.ai.domain.agent.model.Checkpoint;
import cn.ethan.ai.domain.agent.port.driven.ICheckpointRepository;
import cn.ethan.ai.types.common.id.CaseId;
import cn.ethan.ai.types.common.id.CheckpointId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版 Checkpoint 仓储，供单元测试使用。
 *
 * <p>按插入顺序保存，findLatest 返回最后插入的记录，避免时间戳精度问题。</p>
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
    public Optional<Checkpoint> findLatest(CaseId caseId) {
        synchronized (this) {
            for (int i = insertionOrder.size() - 1; i >= 0; i--) {
                Checkpoint cp = checkpoints.get(insertionOrder.get(i));
                if (cp != null && cp.caseId().equals(caseId)) {
                    return Optional.of(cp);
                }
            }
            return Optional.empty();
        }
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
