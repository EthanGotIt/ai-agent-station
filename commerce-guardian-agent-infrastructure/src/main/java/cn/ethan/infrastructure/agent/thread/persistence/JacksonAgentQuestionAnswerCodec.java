package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentQuestionAnswerInput;
import cn.ethan.core.agent.workflow.AgentQuestionCardAnswerActionEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardResumeTargetEnum;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 类型职责：在持久化边界编解码 QuestionCard 回答 Turn 的结构化输入。
 *
 * @author ethan
 * @date 2026-08-27
 */
@Component
public final class JacksonAgentQuestionAnswerCodec {

    private final ObjectMapper objectMapper;

    public JacksonAgentQuestionAnswerCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(AgentQuestionAnswerInput input) {
        if (input == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码 QuestionCard 回答", failure);
        }
    }

    public AgentQuestionAnswerInput decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            JsonNode answersNode = root.path("answers");
            Map<String, String> answers = answersNode.isMissingNode() || answersNode.isNull()
                    ? Map.of() : objectMapper.readValue(answersNode.toString(), new TypeReference<Map<String, String>>() { });
            String runId = root.path("runId").isNull() ? null : root.path("runId").asString(null);
            String target = root.path("resumeTarget").asString(AgentQuestionCardResumeTargetEnum.AGENT.name());
            String action = root.path("action").asString(AgentQuestionCardAnswerActionEnum.SUBMIT.name());
            return new AgentQuestionAnswerInput(
                    root.path("questionId").asString(), runId,
                    AgentQuestionCardResumeTargetEnum.valueOf(target),
                    root.path("enqueuedQuestionVersion").asLong(-1), answers,
                    AgentQuestionCardAnswerActionEnum.valueOf(action));
        } catch (Exception failure) {
            throw new IllegalStateException("无法解码 QuestionCard 回答", failure);
        }
    }

    public String encodeItem(AgentQuestionAnswerInput input) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("schemaVersion", 1);
            root.put("kind", "QUESTION_ANSWER");
            root.set("data", objectMapper.valueToTree(input));
            return objectMapper.writeValueAsString(root);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码 QUESTION_ANSWER Item", failure);
        }
    }
}
