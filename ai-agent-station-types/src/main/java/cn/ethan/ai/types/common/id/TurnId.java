package cn.ethan.ai.types.common.id;

import lombok.NonNull;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次用户补充或人工审批交互的标识。
 */
public record TurnId(String value) {

    public TurnId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TurnId cannot be blank");
        }
    }

    public static TurnId generate() {
        return new TurnId(UUID.randomUUID().toString());
    }

    public static TurnId of(String value) {
        return new TurnId(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TurnId turnId)) return false;
        return Objects.equals(value, turnId.value);
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
