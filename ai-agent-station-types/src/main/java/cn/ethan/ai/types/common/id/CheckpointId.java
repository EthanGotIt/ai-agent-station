package cn.ethan.ai.types.common.id;

import lombok.NonNull;

import java.util.Objects;

/**
 * 状态机 Checkpoint 的唯一标识。
 *
 * <p>Checkpoint 用于在 Plan-and-Execute 循环的每个步骤之后持久化状态，
 * 以便在进程重启或消息重投时从最近一致状态恢复运行。</p>
 */
public record CheckpointId(String value) {

    public CheckpointId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CheckpointId cannot be blank");
        }
    }

    public static CheckpointId of(String value) {
        return new CheckpointId(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CheckpointId checkpointId)) return false;
        return Objects.equals(value, checkpointId.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public @NonNull String toString() {
        return value;
    }
}
