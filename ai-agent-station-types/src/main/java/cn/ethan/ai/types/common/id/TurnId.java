package cn.ethan.ai.types.common.id;

import lombok.NonNull;

import java.util.Objects;

/**
 * 一次外部输入驱动的 Plan-and-Execute 循环标识。
 *
 * <p>一个 Case 可包含多个 Turn；每个 Turn 内部可执行多步 PlannedStep，
 * 并允许在同 Turn 内进行步骤重试与 RePlan，直到返回终态或等待外部输入。</p>
 */
public record TurnId(String value) {

    public TurnId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TurnId cannot be blank");
        }
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
