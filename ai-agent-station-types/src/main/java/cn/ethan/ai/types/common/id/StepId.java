package cn.ethan.ai.types.common.id;

import lombok.NonNull;

import java.util.Objects;

/**
 * PlannedStep 的唯一标识。
 *
 * <p>Step 属于某个 Turn，在 Plan-and-Execute 循环中按顺序执行，
 * 并可在失败后重试，重试耗尽后触发 RePlan 生成新的 Step 集合。</p>
 */
public record StepId(String value) {

    public StepId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("StepId cannot be blank");
        }
    }

    public static StepId of(String value) {
        return new StepId(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StepId stepId)) return false;
        return Objects.equals(value, stepId.value);
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
