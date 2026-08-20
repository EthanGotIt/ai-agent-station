package cn.ethan.infrastructure.agent.workflow.transaction;

import cn.ethan.core.agent.execution.AgentWorkflowAnswerAdmission;
import cn.ethan.core.agent.execution.AgentWorkflowAnswerAdmissionCommand;
import cn.ethan.core.agent.execution.AgentWorkflowAnswerAdmissionResult;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentWorkflowAnswerInput;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
import cn.ethan.infrastructure.agent.thread.persistence.JacksonAgentWorkflowAnswerCodec;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.Map;

/**
 * 类型职责：在单一本地事务中完成 Workflow 回答预留、Turn 首事实和 ENQUEUED 标记。
 *
 * @author ethan
 * @date 2026-08-21
 */
@Component
public final class TransactionalAgentWorkflowAnswerAdmission implements AgentWorkflowAnswerAdmission {

    private final AgentWorkflowQuestionStore questions;
    private final AgentTurnStore turns;
    private final JacksonAgentWorkflowAnswerCodec codec;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public TransactionalAgentWorkflowAnswerAdmission(
            AgentWorkflowQuestionStore questions,
            AgentTurnStore turns,
            JacksonAgentWorkflowAnswerCodec codec,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.questions = questions;
        this.turns = turns;
        this.codec = codec;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public AgentWorkflowAnswerAdmissionResult admit(AgentWorkflowAnswerAdmissionCommand command) {
        AgentWorkflowAnswerAdmissionResult result = transactionTemplate.execute(status -> admitInTransaction(command));
        if (result == null) {
            throw new IllegalStateException("Workflow 回答 admission 事务未返回结果");
        }
        return result;
    }

    private AgentWorkflowAnswerAdmissionResult admitInTransaction(AgentWorkflowAnswerAdmissionCommand command) {
        Optional<AgentTurnModel> duplicate = turns.findTurnByRequest(command.userId(), command.clientRequestId());
        if (duplicate.isPresent()) {
            return duplicateResult(command, duplicate.get());
        }
        AgentWorkflowQuestionModel question = questions.findOpenQuestion(command.userId(), command.threadId())
                .orElseThrow(() -> new AgentThreadConflictException(
                        "QUESTION_NOT_OPEN", "QuestionCard 已关闭或不属于当前 Thread"));
        requireMatchingAvailableQuestion(command, question);
        Map<String, String> validatedAnswers = question.validateAnswers(command.answers());

        String answerTurnId = UUID.randomUUID().toString();
        OptionalLong reservedVersion = questions.reserveAnswerTurn(
                command.userId(), command.questionId(), command.expectedVersion(), answerTurnId);
        if (reservedVersion.isEmpty()) {
            Optional<AgentTurnModel> raced = turns.findTurnByRequestForUpdate(
                    command.userId(), command.clientRequestId());
            if (raced.isPresent()) {
                return duplicateResult(command, raced.get());
            }
            throw versionConflict();
        }
        long expectedEnqueuedVersion = reservedVersion.getAsLong() + 1;
        AgentWorkflowAnswerInput answerInput = new AgentWorkflowAnswerInput(
                command.runId(), command.questionId(), command.checkpointId(),
                expectedEnqueuedVersion, validatedAnswers);
        Instant createdAt = clock.instant();
        AgentTurnModel turn = new AgentTurnModel(
                answerTurnId, command.threadId(), command.userId(), command.clientRequestId(),
                "QuestionCard 回答", AgentTurnStatusEnum.QUEUED, command.queuePosition(), command.runId(),
                null, createdAt, null, null, answerInput);
        AgentItemModel initialItem = new AgentItemModel(
                UUID.randomUUID().toString(), command.threadId(), answerTurnId, 0,
                AgentItemTypeEnum.WORKFLOW_ANSWER, codec.encodeItem(answerInput), createdAt);
        long sequence = turns.createTurnWithInitialItem(turn, initialItem);
        if (sequence < 1) {
            throw new IllegalStateException("Workflow 回答 admission 必须原子持久化首个 Item");
        }
        OptionalLong enqueuedVersion = questions.markAnswerTurnEnqueued(
                command.userId(), command.questionId(), reservedVersion.getAsLong(), answerTurnId);
        if (enqueuedVersion.isEmpty() || enqueuedVersion.getAsLong() != expectedEnqueuedVersion) {
            throw versionConflict();
        }
        AgentItemModel persistedItem = new AgentItemModel(
                initialItem.itemId(), initialItem.threadId(), initialItem.turnId(), sequence,
                initialItem.type(), initialItem.payload(), initialItem.createdAt());
        return new AgentWorkflowAnswerAdmissionResult(
                turn, enqueuedVersion.getAsLong(), persistedItem, true);
    }

    private AgentWorkflowAnswerAdmissionResult duplicateResult(
            AgentWorkflowAnswerAdmissionCommand command,
            AgentTurnModel existing
    ) {
        AgentWorkflowAnswerInput input = existing.workflowAnswerInput();
        boolean matches = existing.userId().equals(command.userId())
                && existing.threadId().equals(command.threadId())
                && input != null
                && input.runId().equals(command.runId())
                && input.questionId().equals(command.questionId())
                && input.checkpointId().equals(command.checkpointId())
                && input.admissionExpectedVersion() == command.expectedVersion()
                && input.answers().equals(command.answers());
        if (!matches) {
            throw new AgentThreadConflictException(
                    "CLIENT_REQUEST_CONFLICT", "clientRequestId 已用于不同的 Workflow 回答");
        }
        return new AgentWorkflowAnswerAdmissionResult(
                existing, input.enqueuedQuestionVersion(), null, false);
    }

    private void requireMatchingAvailableQuestion(
            AgentWorkflowAnswerAdmissionCommand command,
            AgentWorkflowQuestionModel question
    ) {
        if (!question.userId().equals(command.userId())
                || !question.threadId().equals(command.threadId())
                || !question.runId().equals(command.runId())
                || !question.questionId().equals(command.questionId())
                || !question.checkpointId().equals(command.checkpointId())
                || question.version() != command.expectedVersion()
                || question.status() != AgentWorkflowQuestionStatusEnum.OPEN
                || question.answerEnqueueStatus()
                != AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE
                || question.answerTurnId() != null) {
            throw versionConflict();
        }
    }

    private AgentThreadConflictException versionConflict() {
        return new AgentThreadConflictException(
                "WORKFLOW_VERSION_CONFLICT", "QuestionCard 检查点、回答预留或版本已变化");
    }
}
