package cn.ethan.core.workflow.support;

import cn.ethan.core.workflow.enums.WorkflowRunStatusEnum;
import cn.ethan.core.workflow.exception.WorkflowRunConflictException;
import cn.ethan.core.workflow.exception.WorkflowRunNotFoundException;
import cn.ethan.core.workflow.model.WorkflowAnswerRequestModel;
import cn.ethan.core.workflow.model.WorkflowRunModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;

/**
 * Workflow 回答支持：统一校验等待中的 QuestionCard 信封并生成稳定重试摘要。
 *
 * @author ethan
 * @date 2026-08-10
 */
public final class WorkflowAnswerSupport {

    private WorkflowAnswerSupport() {
    }

    public static void validatePendingEnvelope(
            WorkflowAnswerRequestModel request,
            WorkflowRunModel run,
            String workflowId,
            String domainId
    ) {
        if (!workflowId.equals(run.workflowId()) || !domainId.equals(run.domainId())) {
            throw new WorkflowRunNotFoundException(request.runId());
        }
        if (run.status() != WorkflowRunStatusEnum.WAITING_USER_INPUT || run.question() == null
                || !run.question().questionId().equals(request.questionId())
                || !run.checkpointId().equals(request.checkpointId())
                || run.version() != request.expectedVersion()) {
            throw new WorkflowRunConflictException("workflow run checkpoint or version has changed");
        }
    }

    public static String answerDigest(Map<String, String> answers) {
        String source = answers.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce("", (left, right) -> left + ";" + right);
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
