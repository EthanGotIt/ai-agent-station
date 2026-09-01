package cn.ethan.app.agent.api;

import cn.ethan.core.agent.workflow.AgentQuestionCardAnswerActionEnum;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证普通 Turn 与 QuestionCard 回答 HTTP 输入统一遵守持久化边界。
 *
 * @author ethan
 * @date 2026-08-21
 */
class AgentClientRequestIdValidationTest {

    @Test
    void validatesClientRequestIdAt128CharacterDatabaseBoundary() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            String accepted = "a".repeat(128);
            String rejected = "a".repeat(129);
            String acceptedMessage = "m".repeat(256);
            String rejectedMessage = "m".repeat(257);

            assertTrue(validator.validate(new AgentTurnSubmitRequestDto(accepted, acceptedMessage)).isEmpty());
            assertFalse(validator.validate(new AgentTurnSubmitRequestDto(rejected, "message")).isEmpty());
            assertFalse(validator.validate(new AgentTurnSubmitRequestDto(accepted, rejectedMessage)).isEmpty());
            assertTrue(validator.validate(new AgentQuestionAnswerRequestDto(
                    accepted, 0L, Map.of("answer", "value"), null)).isEmpty());
            assertFalse(validator.validate(new AgentQuestionAnswerRequestDto(
                    rejected, 0L, Map.of("answer", "value"), null)).isEmpty());
            assertTrue(validator.validate(new AgentQuestionAnswerRequestDto(
                    accepted, 0L, null, AgentQuestionCardAnswerActionEnum.CANCEL)).isEmpty());
        }
    }
}
