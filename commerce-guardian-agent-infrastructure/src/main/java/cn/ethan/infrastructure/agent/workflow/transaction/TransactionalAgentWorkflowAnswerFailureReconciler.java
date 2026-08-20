package cn.ethan.infrastructure.agent.workflow.transaction;

import cn.ethan.core.agent.execution.AgentWorkflowAnswerFailureReconciler;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentWorkflowAnswerInput;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

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
    private final TransactionTemplate transactionTemplate;

    public TransactionalAgentWorkflowAnswerFailureReconciler(
            AgentWorkflowQuestionStore questions,
            AgentTurnStore turns,
            PlatformTransactionManager transactionManager
    ) {
        this.questions = questions;
        this.turns = turns;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public boolean reconcile(
            AgentTurnModel turn,
            AgentTurnStatusEnum terminalStatus,
            String errorCode,
            Instant finishedAt
    ) {
        Boolean result = transactionTemplate.execute(status -> reconcileInTransaction(
                turn, terminalStatus, errorCode, finishedAt));
        return Boolean.TRUE.equals(result);
    }

    private boolean reconcileInTransaction(
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
                return true;
            }
        }
        if (!isTerminal(turn.status())) {
            AgentTurnModel terminal = turn.terminal(terminalStatus, errorCode, finishedAt);
            if (!turns.updateTurn(turn, terminal)) {
                throw new AgentThreadConflictException("TURN_VERSION_CONFLICT", "回答失败对账时 Turn 版本竞争：" + turn.turnId());
            }
        }
        return true;
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
