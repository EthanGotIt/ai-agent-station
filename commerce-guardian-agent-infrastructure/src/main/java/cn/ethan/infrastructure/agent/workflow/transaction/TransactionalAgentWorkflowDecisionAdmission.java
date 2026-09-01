package cn.ethan.infrastructure.agent.workflow.transaction;

import cn.ethan.core.agent.execution.AgentTurnItemPayloads;
import cn.ethan.core.agent.execution.AgentWorkflowDecisionAdmission;
import cn.ethan.core.agent.execution.AgentWorkflowDecisionAdmissionCommand;
import cn.ethan.core.agent.execution.AgentWorkflowDecisionAdmissionResult;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentWorkflowDecisionInput;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointModel;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 类型职责：在本地事务中记录 Workflow Checkpoint 决策 Turn，并以事实指纹完成决策 CAS。
 *
 * @author ethan
 * @date 2026-08-27
 */
@Component
public final class TransactionalAgentWorkflowDecisionAdmission implements AgentWorkflowDecisionAdmission {

    private final AgentWorkflowCheckpointStore checkpoints;
    private final AgentTurnStore turns;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public TransactionalAgentWorkflowDecisionAdmission(
            AgentWorkflowCheckpointStore checkpoints,
            AgentTurnStore turns,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.checkpoints = checkpoints;
        this.turns = turns;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public AgentWorkflowDecisionAdmissionResult admit(AgentWorkflowDecisionAdmissionCommand command) {
        AgentWorkflowDecisionAdmissionResult result = transactionTemplate.execute(status -> admitInTransaction(command));
        if (result == null) {
            throw new IllegalStateException("Workflow Checkpoint 决策 admission 事务未返回结果");
        }
        return result;
    }

    private AgentWorkflowDecisionAdmissionResult admitInTransaction(AgentWorkflowDecisionAdmissionCommand command) {
        AgentWorkflowCheckpointModel checkpoint = checkpoints.find(command.userId(), command.checkpointId())
                .orElseThrow(() -> new AgentThreadConflictException("CHECKPOINT_NOT_FOUND", "Workflow Checkpoint 不存在"));
        Optional<AgentTurnModel> duplicate = turns.findTurnByRequest(command.userId(), command.clientRequestId());
        if (duplicate.isPresent()) {
            return duplicateResult(command, checkpoint, duplicate.get());
        }
        requireAvailable(checkpoint, command);

        AgentWorkflowDecisionInput input = new AgentWorkflowDecisionInput(
                command.runId(), command.checkpointId(), command.expectedVersion(),
                command.decision(), command.factsFingerprint());
        Instant createdAt = clock.instant();
        String turnId = UUID.randomUUID().toString();
        AgentTurnModel turn = new AgentTurnModel(
                turnId, checkpoint.threadId(), checkpoint.userId(), command.clientRequestId(),
                "Workflow Checkpoint 决策", AgentTurnStatusEnum.QUEUED, 1, command.runId(), null,
                createdAt, null, null, null, 0L,
                cn.ethan.core.agent.thread.AgentTurnInputKindEnum.WORKFLOW_DECISION, null, null, input);
        AgentItemModel initialItem = new AgentItemModel(
                UUID.randomUUID().toString(), turn.threadId(), turn.turnId(), 0,
                AgentItemTypeEnum.WORKFLOW_DECISION, AgentTurnItemPayloads.workflowDecision(input), createdAt);
        long sequence = turns.createTurnWithInitialItem(turn, initialItem);
        if (sequence < 1) {
            throw new IllegalStateException("Workflow Checkpoint 决策必须原子持久化首个 Item");
        }
        boolean decided = checkpoints.decide(command.userId(), command.checkpointId(), command.expectedVersion(),
                command.decision(), command.factsFingerprint());
        if (!decided) {
            AgentWorkflowCheckpointModel current = checkpoints.find(command.userId(), command.checkpointId()).orElse(null);
            if (current == null || current.status() != AgentWorkflowCheckpointStatusEnum.SUPERSEDED) {
                throw new AgentThreadConflictException(
                        "CHECKPOINT_VERSION_CONFLICT", "Workflow Checkpoint 版本或开放指针已变化");
            }
        }
        AgentItemModel persistedItem = new AgentItemModel(
                initialItem.itemId(), initialItem.threadId(), initialItem.turnId(), sequence,
                initialItem.type(), initialItem.payload(), initialItem.createdAt());
        return new AgentWorkflowDecisionAdmissionResult(turn, persistedItem, true);
    }

    private AgentWorkflowDecisionAdmissionResult duplicateResult(
            AgentWorkflowDecisionAdmissionCommand command,
            AgentWorkflowCheckpointModel checkpoint,
            AgentTurnModel existing
    ) {
        AgentWorkflowDecisionInput input = existing.workflowDecisionInput();
        boolean matches = existing.userId().equals(command.userId())
                && existing.threadId().equals(checkpoint.threadId())
                && input != null
                && input.runId().equals(command.runId())
                && input.checkpointId().equals(command.checkpointId())
                && input.expectedVersion() == command.expectedVersion()
                && input.decision() == command.decision()
                && input.factsFingerprint().equals(command.factsFingerprint());
        if (!matches) {
            throw new AgentThreadConflictException(
                    "CLIENT_REQUEST_CONFLICT", "clientRequestId 已用于不同的 Workflow Checkpoint 决策");
        }
        return new AgentWorkflowDecisionAdmissionResult(existing, null, false);
    }

    private void requireAvailable(AgentWorkflowCheckpointModel checkpoint,
                                  AgentWorkflowDecisionAdmissionCommand command) {
        if (!checkpoint.runId().equals(command.runId())
                || checkpoint.status() != AgentWorkflowCheckpointStatusEnum.OPEN
                || checkpoint.version() != command.expectedVersion()) {
            throw new AgentThreadConflictException(
                    "CHECKPOINT_VERSION_CONFLICT", "Workflow Checkpoint 版本或所属 Workflow 已变化");
        }
    }
}
