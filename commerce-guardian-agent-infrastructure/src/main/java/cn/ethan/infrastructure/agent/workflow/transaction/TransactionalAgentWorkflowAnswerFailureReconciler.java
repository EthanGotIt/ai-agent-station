package cn.ethan.infrastructure.agent.workflow.transaction;

import cn.ethan.core.agent.execution.AgentWorkflowAnswerFailureReconciler;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentWorkflowAnswerInput;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * 类型职责：在一个本地事务中释放失败回答的 Question 绑定并收敛 Turn 终态。
 *
 * @author ethan
 * @date 2026-08-21
 */
@Component
public final class TransactionalAgentWorkflowAnswerFailureReconciler
        implements AgentWorkflowAnswerFailureReconciler {

    private final AgentWorkflowQuestionStore questions;
    private final AgentTurnStore turns;
    private final AgentItemStore items;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public TransactionalAgentWorkflowAnswerFailureReconciler(
            AgentWorkflowQuestionStore questions,
            AgentTurnStore turns,
            AgentItemStore items,
            PlatformTransactionManager transactionManager
    ) {
        this.questions = questions;
        this.turns = turns;
        this.items = items;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean reconcile(
            AgentTurnModel turn,
            AgentTurnStatusEnum terminalStatus,
            String errorCode,
            Instant finishedAt
    ) {
        return reconcileWithProjection(turn, terminalStatus, errorCode, finishedAt).reconciled();
    }

    @Override
    public ReconciliationResult reconcileWithProjection(
            AgentTurnModel turn,
            AgentTurnStatusEnum terminalStatus,
            String errorCode,
            Instant finishedAt
    ) {
        ReconciliationResult result = transactionTemplate.execute(status -> reconcileInTransaction(
                turn, terminalStatus, errorCode, finishedAt));
        return result == null ? new ReconciliationResult(false, null) : result;
    }

    private ReconciliationResult reconcileInTransaction(
            AgentTurnModel turn,
            AgentTurnStatusEnum terminalStatus,
            String errorCode,
            Instant finishedAt
    ) {
        AgentWorkflowAnswerInput input = turn.workflowAnswerInput();
        if (input == null || finishedAt == null || !isFailureTerminal(terminalStatus)) {
            throw new IllegalArgumentException("回答失败对账参数不合法");
        }
        boolean released = questions.releaseAnswerTurn(
                turn.userId(), input.questionId(), input.enqueuedQuestionVersion(), turn.turnId());
        if (!released) {
            AgentWorkflowQuestionModel openQuestion = questions.findOpenQuestionByRun(
                    turn.userId(), input.runId()).orElse(null);
            if (stillBoundToFailedTurn(openQuestion, turn, input)) {
                throw new IllegalStateException("回答 QuestionCard 仍为同 Turn 的 ENQUEUED 状态");
            }
            AgentTurnModel current = turns.findTurn(turn.userId(), turn.turnId()).orElse(null);
            if (current != null && isTerminal(current.status())) {
                return new ReconciliationResult(true, null);
            }
        }
        AgentItemModel retryQuestionItem = retryQuestionItem(turn, input, finishedAt);
        if (!isTerminal(turn.status())) {
            AgentTurnModel terminal = turn.terminal(terminalStatus, errorCode, finishedAt);
            if (!turns.updateTurn(turn, terminal)) {
                throw new AgentThreadConflictException("TURN_VERSION_CONFLICT", "回答失败对账时 Turn 版本竞争：" + turn.turnId());
            }
        }
        return new ReconciliationResult(true, retryQuestionItem);
    }

    private AgentItemModel retryQuestionItem(
            AgentTurnModel turn,
            AgentWorkflowAnswerInput input,
            Instant createdAt
    ) {
        AgentWorkflowQuestionModel question = questions.findOpenQuestionByRun(turn.userId(), input.runId())
                .filter(value -> value.questionId().equals(input.questionId()))
                .filter(value -> value.status() == AgentWorkflowQuestionStatusEnum.OPEN)
                .filter(value -> value.answerEnqueueStatus()
                        == AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE)
                .orElse(null);
        if (question == null) {
            return null;
        }
        AgentItemModel draft = new AgentItemModel(
                UUID.randomUUID().toString(), question.threadId(), question.turnId(), 0,
                AgentItemTypeEnum.WORKFLOW_QUESTION, questionPayload(question), createdAt);
        long sequence = items.appendItem(draft);
        if (sequence < 1) {
            throw new IllegalStateException("失败回答重试 Question Item 未分配有效序号");
        }
        return new AgentItemModel(draft.itemId(), draft.threadId(), draft.turnId(), sequence,
                draft.type(), draft.payload(), draft.createdAt());
    }

    private String questionPayload(AgentWorkflowQuestionModel question) {
        return "{\"runId\":\"" + escape(question.runId()) + "\",\"questionId\":\""
                + escape(question.questionId()) + "\",\"checkpointId\":\"" + escape(question.checkpointId())
                + "\",\"version\":" + question.version() + ",\"title\":\"" + escape(question.title())
                + "\",\"prompt\":\"" + escape(question.prompt()) + "\",\"fields\":"
                + question.fieldsJson() + "}";
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private boolean stillBoundToFailedTurn(
            AgentWorkflowQuestionModel question,
            AgentTurnModel turn,
            AgentWorkflowAnswerInput input
    ) {
        return question != null
                && question.status() == AgentWorkflowQuestionStatusEnum.OPEN
                && question.questionId().equals(input.questionId())
                && turn.turnId().equals(question.answerTurnId())
                && question.answerEnqueueStatus()
                == AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED;
    }

    private boolean isFailureTerminal(AgentTurnStatusEnum status) {
        return status == AgentTurnStatusEnum.FAILED
                || status == AgentTurnStatusEnum.CANCELLED
                || status == AgentTurnStatusEnum.TIMED_OUT;
    }

    private boolean isTerminal(AgentTurnStatusEnum status) {
        return status == AgentTurnStatusEnum.COMPLETED
                || status == AgentTurnStatusEnum.CANCELLED
                || status == AgentTurnStatusEnum.TIMED_OUT
                || status == AgentTurnStatusEnum.FAILED;
    }
}
