package cn.ethan.core.workflow.model;

import cn.ethan.core.agent.model.AgentMemoryOptionsModel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Workflow 回答请求模型：携带用户对持久化 QuestionCard 的显式答案。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record WorkflowAnswerRequestModel(
        String requestId,
        String sessionId,
        String runId,
        String questionId,
        String checkpointId,
        long expectedVersion,
        Map<String, String> answers,
        AgentMemoryOptionsModel memory
) {

    public WorkflowAnswerRequestModel(
            String requestId,
            String sessionId,
            String runId,
            String questionId,
            String checkpointId,
            long expectedVersion,
            Map<String, String> answers
    ) {
        this(requestId, sessionId, runId, questionId, checkpointId, expectedVersion,
                answers, AgentMemoryOptionsModel.DEFAULT);
    }

    public WorkflowAnswerRequestModel {
        require(requestId, "requestId");
        require(sessionId, "sessionId");
        require(runId, "runId");
        require(questionId, "questionId");
        require(checkpointId, "checkpointId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("workflow answer version is invalid");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        if (answers != null) {
            answers.forEach((key, value) -> {
                require(key, "answer name");
                require(value, "answer value");
                normalized.put(key.strip(), value.strip());
            });
        }
        answers = Map.copyOf(normalized);
        if (answers.isEmpty()) {
            throw new IllegalArgumentException("workflow answers are required");
        }
        memory = memory == null ? AgentMemoryOptionsModel.DEFAULT : memory;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
