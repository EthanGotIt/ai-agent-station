package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentWorkflowAnswerInput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
            return objectMapper.writeValueAsString(input.answers());
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
            Map<String, String> answers = objectMapper.readValue(
                    answersJson, new TypeReference<Map<String, String>>() { });
            return new AgentWorkflowAnswerInput(
                    runId, questionId, checkpointId, enqueuedQuestionVersion, answers);
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
        data.set("answers", objectMapper.valueToTree(input.answers()));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码 WORKFLOW_ANSWER Item", failure);
        }
    }
}
