package cn.ethan.infrastructure.agent.workflow.transaction;

import cn.ethan.core.agent.execution.AgentQuestionAnswerAdmission;
import cn.ethan.core.agent.execution.AgentQuestionAnswerAdmissionCommand;
import cn.ethan.core.agent.execution.AgentQuestionAnswerAdmissionResult;
import cn.ethan.core.agent.execution.AgentTurnItemPayloads;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentQuestionAnswerInput;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.workflow.AgentQuestionCardAnswerActionEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardModel;
import cn.ethan.core.agent.workflow.AgentQuestionCardStatusEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 类型职责：在单一本地事务中完成 QuestionCard 回答预留、Turn 首事实和入队状态更新。
 *
 * @author ethan
 * @date 2026-08-27
 */
@Component
public final class TransactionalAgentQuestionAnswerAdmission implements AgentQuestionAnswerAdmission {

    private final AgentQuestionCardStore questions;
    private final AgentTurnStore turns;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public TransactionalAgentQuestionAnswerAdmission(
            AgentQuestionCardStore questions,
            AgentTurnStore turns,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.questions = questions;
        this.turns = turns;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public AgentQuestionAnswerAdmissionResult admit(AgentQuestionAnswerAdmissionCommand command) {
        AgentQuestionAnswerAdmissionResult result = transactionTemplate.execute(status -> admitInTransaction(command));
        if (result == null) {
            throw new IllegalStateException("QuestionCard 回答 admission 事务未返回结果");
        }
        return result;
    }

    private AgentQuestionAnswerAdmissionResult admitInTransaction(AgentQuestionAnswerAdmissionCommand command) {
        AgentQuestionCardModel question = questions.find(command.userId(), command.questionId())
                .orElseThrow(() -> new AgentThreadConflictException("QUESTION_NOT_FOUND", "QuestionCard 不存在"));
        Map<String, String> validated = command.action() == AgentQuestionCardAnswerActionEnum.CANCEL
                ? Map.of() : question.validateAnswers(command.answers());
        Optional<AgentTurnModel> duplicate = turns.findTurnByRequest(command.userId(), command.clientRequestId());
        if (duplicate.isPresent()) {
            return duplicateResult(command, question, duplicate.get(), validated);
        }
        requireAvailable(question, command);

        String answerTurnId = UUID.randomUUID().toString();
        OptionalLong reservedVersion = questions.reserveAnswerTurn(
                command.userId(), command.questionId(), command.expectedVersion(), answerTurnId);
        if (reservedVersion.isEmpty()) {
            Optional<AgentTurnModel> raced = turns.findTurnByRequestForUpdate(
                    command.userId(), command.clientRequestId());
            if (raced.isPresent()) {
                return duplicateResult(command, question, raced.get(), validated);
            }
            throw versionConflict();
        }
        long enqueuedVersion = reservedVersion.getAsLong() + 1;
        AgentQuestionAnswerInput input = new AgentQuestionAnswerInput(
                question.questionId(), question.runId(), question.resumeTarget(), enqueuedVersion,
                validated, command.action());
        Instant createdAt = clock.instant();
        AgentTurnModel turn = new AgentTurnModel(
                answerTurnId, question.threadId(), question.userId(), command.clientRequestId(),
                "QuestionCard 回答", AgentTurnStatusEnum.QUEUED, 1, question.runId(), null,
                createdAt, null, null, input);
        AgentItemModel initialItem = new AgentItemModel(
                UUID.randomUUID().toString(), turn.threadId(), turn.turnId(), 0,
                AgentItemTypeEnum.QUESTION_ANSWER, AgentTurnItemPayloads.questionAnswer(input), createdAt);
        long sequence = turns.createTurnWithInitialItem(turn, initialItem);
        if (sequence < 1) {
            throw new IllegalStateException("QuestionCard 回答 admission 必须原子持久化首个 Item");
        }
        OptionalLong marked = questions.markAnswerTurnEnqueued(
                command.userId(), command.questionId(), reservedVersion.getAsLong(), answerTurnId);
        if (marked.isEmpty() || marked.getAsLong() != enqueuedVersion) {
            throw versionConflict();
        }
        AgentItemModel persistedItem = new AgentItemModel(
                initialItem.itemId(), initialItem.threadId(), initialItem.turnId(), sequence,
                initialItem.type(), initialItem.payload(), initialItem.createdAt());
        return new AgentQuestionAnswerAdmissionResult(turn, persistedItem, true);
    }

    private AgentQuestionAnswerAdmissionResult duplicateResult(
            AgentQuestionAnswerAdmissionCommand command,
            AgentQuestionCardModel question,
            AgentTurnModel existing,
            Map<String, String> validated
    ) {
        var input = existing.questionAnswerInput();
        boolean matches = existing.userId().equals(command.userId())
                && existing.threadId().equals(question.threadId())
                && input != null
                && input.questionId().equals(command.questionId())
                && input.admissionExpectedVersion() == command.expectedVersion()
                && input.action() == command.action()
                && input.answers().equals(validated);
        if (!matches) {
            throw new AgentThreadConflictException(
                    "CLIENT_REQUEST_CONFLICT", "clientRequestId 已用于不同的 QuestionCard 回答");
        }
        return new AgentQuestionAnswerAdmissionResult(existing, null, false);
    }

    private void requireAvailable(AgentQuestionCardModel question, AgentQuestionAnswerAdmissionCommand command) {
        if (question.status() != AgentQuestionCardStatusEnum.OPEN
                || question.version() != command.expectedVersion()
                || question.answerEnqueueStatus()
                != cn.ethan.core.agent.workflow.AgentQuestionCardAnswerEnqueueStatusEnum.AVAILABLE) {
            throw versionConflict();
        }
    }

    private AgentThreadConflictException versionConflict() {
        return new AgentThreadConflictException(
                "QUESTION_VERSION_CONFLICT", "QuestionCard 检查点、回答预留或版本已变化");
    }
}
