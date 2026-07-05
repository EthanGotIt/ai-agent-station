package cn.ethan.ai.types.common.id;

import lombok.NonNull;

import java.util.Objects;
import java.util.UUID;

/**
 * 售后 Case 的唯一标识，同时作为状态机 threadId。
 */
public record CaseId(String value) {

    public CaseId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CaseId cannot be blank");
        }
    }

    public static CaseId generate() {
        return new CaseId(UUID.randomUUID().toString());
    }

    public static CaseId of(String value) {
        return new CaseId(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CaseId caseId)) return false;
        return Objects.equals(value, caseId.value);
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
