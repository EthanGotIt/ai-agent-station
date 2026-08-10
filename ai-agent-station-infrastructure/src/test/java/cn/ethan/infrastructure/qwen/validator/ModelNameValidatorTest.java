package cn.ethan.infrastructure.qwen.validator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 模型名称校验器测试：验证角色模型族和快照格式白名单。
 *
 * @author ethan
 * @date 2026-08-05
 */
class ModelNameValidatorTest {

    @Test
    void acceptsDefaultModelFamilies() {
        ModelNameValidator validator = new ModelNameValidator(
                "qwen3.7-plus",
                "qwen3.7-plus"
        );

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void acceptsDatedSnapshots() {
        ModelNameValidator validator = new ModelNameValidator(
                "qwen3.7-plus-20260805",
                "qwen3.7-plus-2026-08-05"
        );

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void acceptsMaxOnlyForReactBenchmark() {
        ModelNameValidator validator = new ModelNameValidator(
                "qwen3.7-plus",
                "qwen3.8-max"
        );

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void rejectsNonQwen37Model() {
        ModelNameValidator validator = new ModelNameValidator(
                "qwen3-flash",
                "qwen3.7-plus"
        );

        assertThrows(IllegalStateException.class, validator::validate);
    }
}
