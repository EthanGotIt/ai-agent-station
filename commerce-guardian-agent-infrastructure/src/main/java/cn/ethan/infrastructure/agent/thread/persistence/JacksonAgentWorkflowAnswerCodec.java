package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentWorkflowAnswerInput;
import cn.ethan.core.agent.workflow.AgentWorkflowAnswerActionEnum;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 类型职责：在 Infrastructure 边界编解码回答字段，并生成稳定的 WORKFLOW_ANSWER Item envelope。
 *
 * @author ethan
 * @date 2026-08-21
 */
@Component
public final class JacksonAgentWorkflowAnswerCodec {

    private final ObjectMapper objectMapper;

    public JacksonAgentWorkflowAnswerCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encodeAnswers(AgentWorkflowAnswerInput input) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("action", input.action().name());
            root.set("answers", objectMapper.valueToTree(input.answers()));
            return objectMapper.writeValueAsString(root);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码 Workflow 回答字段", failure);
        }
    }

    public AgentWorkflowAnswerInput decode(
            String runId,
            String questionId,
            String checkpointId,
            long enqueuedQuestionVersion,
            String answersJson
    ) {
        try {
            JsonNode root = objectMapper.readTree(answersJson);
            AgentWorkflowAnswerActionEnum action = AgentWorkflowAnswerActionEnum.SUBMIT;
            JsonNode answersNode = root;
            if (root != null && root.isObject() && root.has("answers")) {
                String actionValue = root.path("action").asString();
                if (actionValue != null && !actionValue.isBlank()) {
                    action = AgentWorkflowAnswerActionEnum.valueOf(actionValue);
                }
                answersNode = root.path("answers");
            }
            Map<String, String> answers = objectMapper.readValue(
                    answersNode.toString(), new TypeReference<Map<String, String>>() { });
            return new AgentWorkflowAnswerInput(
                    runId, questionId, checkpointId, enqueuedQuestionVersion, answers, action);
        } catch (Exception failure) {
            throw new IllegalStateException("无法解码 Workflow 回答字段", failure);
        }
    }

    public String encodeItem(AgentWorkflowAnswerInput input) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("kind", "WORKFLOW_ANSWER");
        ObjectNode data = root.putObject("data");
        data.put("runId", input.runId());
        data.put("questionId", input.questionId());
        data.put("checkpointId", input.checkpointId());
        data.put("enqueuedQuestionVersion", input.enqueuedQuestionVersion());
        data.put("action", input.action().name());
        data.set("answers", objectMapper.valueToTree(input.answers()));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码 WORKFLOW_ANSWER Item", failure);
        }
    }
}
