package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentQuestionAnswerInput;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.workflow.AgentQuestionCardAnswerActionEnum;

import java.util.Map;

/**
 * 类型职责：描述一次 QuestionCard 回答 admission 请求，供事务适配器完成版本 CAS 和 Turn 创建。
 *
 * @author ethan
 * @date 2026-08-27
 */
public record AgentQuestionAnswerAdmissionCommand(
        String userId,
        String questionId,
        String clientRequestId,
        long expectedVersion,
        Map<String, String> answers,
        AgentQuestionCardAnswerActionEnum action
) {

    public AgentQuestionAnswerAdmissionCommand {
        userId = identity(userId, "userId", AgentThreadModel.MAX_USER_ID_LENGTH);
        questionId = identity(questionId, "questionId", AgentThreadModel.MAX_THREAD_ID_LENGTH);
        clientRequestId = identity(clientRequestId, "clientRequestId", AgentTurnModel.MAX_CLIENT_REQUEST_ID_LENGTH);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("QuestionCard expectedVersion 不能为负数");
        }
        action = action == null ? AgentQuestionCardAnswerActionEnum.SUBMIT : action;
        answers = answers == null ? Map.of() : Map.copyOf(answers);
        if (action == AgentQuestionCardAnswerActionEnum.CANCEL) {
            answers = Map.of();
        }
    }

    private static String identity(String value, String name, int maxLength) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " 不能为空且长度不能超过 " + maxLength);
        }
        return normalized;
    }
}
