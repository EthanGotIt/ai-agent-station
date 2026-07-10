package cn.ethan.ai.domain.agent.port.driven;

import cn.ethan.ai.domain.agent.model.Checkpoint;
import cn.ethan.ai.types.common.id.CheckpointId;

import java.util.Optional;

/**
 * Checkpoint 持久化端口。
 */
public interface ICheckpointRepository {

    void save(Checkpoint checkpoint);

    Optional<Checkpoint> findById(CheckpointId checkpointId);
}
