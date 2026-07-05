package cn.ethan.ai.types.common.id;

import lombok.NonNull;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Graph start/resume/retry 尝试的标识。
 */
public record RunId(String value) {

    public RunId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("RunId cannot be blank");
        }
    }

    public static RunId generate() {
        return new RunId(UUID.randomUUID().toString());
    }

    public static RunId of(String value) {
        return new RunId(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RunId runId)) return false;
        return Objects.equals(value, runId.value);
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
