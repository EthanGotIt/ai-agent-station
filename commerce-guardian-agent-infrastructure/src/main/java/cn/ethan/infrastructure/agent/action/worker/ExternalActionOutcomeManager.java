package cn.ethan.infrastructure.agent.action.worker;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 类型职责：在一个本地事务内收敛外部动作命令及其 Workflow、Turn 和 Item 事实。
 *
 * @author ethan
 * @date 2026-08-21
 */
@Component
public final class ExternalActionOutcomeManager {

    private final ExternalActionCommandStore commands;
    private final AgentItemStore items;
    private final AgentTurnStore turns;
    private final AgentWorkflowRunStore workflowRuns;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public ExternalActionOutcomeManager(
            ExternalActionCommandStore commands,
            AgentItemStore items,
            AgentTurnStore turns,
            AgentWorkflowRunStore workflowRuns,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this(commands, items, turns, workflowRuns, objectMapper,
                transactionManager == null ? null : new TransactionTemplate(transactionManager));
    }

    /**
     * 为不依赖 Spring 事务容器的内存测试提供同样的投影逻辑。
     */
    public ExternalActionOutcomeManager(
            ExternalActionCommandStore commands,
            AgentItemStore items,
            AgentTurnStore turns,
            AgentWorkflowRunStore workflowRuns,
            ObjectMapper objectMapper
    ) {
        this(commands, items, turns, workflowRuns, objectMapper, (TransactionTemplate) null);
    }

    private ExternalActionOutcomeManager(
            ExternalActionCommandStore commands,
            AgentItemStore items,
            AgentTurnStore turns,
            AgentWorkflowRunStore workflowRuns,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.commands = commands;
        this.items = items;
        this.turns = turns;
        this.workflowRuns = workflowRuns;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 只有命令 CAS 成功后才允许生成其他事实；事务失败时命令和全部投影一起回滚。
     *
     * @return 已提交的投影；Lease 或版本竞争失败时返回空
     */
    public Projection transition(
            ExternalActionCommandModel expected,
            ExternalActionCommandModel next,
            String resultCode,
            String resultMessage,
            Clock clock
    ) {
        Instant now = clock.instant();
        if (transactionTemplate == null) {
            return project(expected, next, resultCode, resultMessage, now);
        }
        return transactionTemplate.execute(status -> project(expected, next, resultCode, resultMessage, now));
    }

    private Projection project(
            ExternalActionCommandModel expected,
            ExternalActionCommandModel next,
            String resultCode,
            String resultMessage,
            Instant now
    ) {
        if (!commands.update(expected, next)) {
            return null;
        }

        AgentWorkflowRunModel run = workflowRuns.find(next.userId(), next.runId())
                .orElseThrow(() -> new IllegalStateException("外部动作对应的 WorkflowRun 不存在：" + next.runId()));
        AgentWorkflowStatusEnum targetWorkflowStatus = workflowStatus(next.status());
        if (run.status() != targetWorkflowStatus) {
            if (isTerminal(run.status())) {
                throw new IllegalStateException("WorkflowRun 已处于冲突终态：" + run.runId());
            }
            workflowRuns.update(run.status(targetWorkflowStatus, now));
        }

        List<AgentItemModel> projectedItems = new ArrayList<>();
        projectedItems.add(appendStatus(next, resultCode, resultMessage, now));

        AgentTurnModel projectedTurn = projectTurn(next, now);
        if (projectedTurn != null) {
            projectedItems.add(appendTurnState(projectedTurn, now));
        }
        return new Projection(next, projectedTurn, projectedItems);
    }

    private AgentTurnModel projectTurn(ExternalActionCommandModel command, Instant now) {
        if (command.turnId() == null || command.turnId().isBlank()) {
            return null;
        }
        Optional<AgentTurnModel> current = turns.findTurn(command.userId(), command.turnId());
        if (current.isEmpty() || isTerminal(current.get().status())) {
            return null;
        }
        AgentTurnStatusEnum target = turnStatus(command.status());
        AgentTurnModel nextTurn = target == AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION
                ? current.get().workflow(command.runId(), target)
                : current.get().terminal(target,
                target == AgentTurnStatusEnum.FAILED ? "EXTERNAL_ACTION_FAILED" : null, now);
        turns.updateTurn(nextTurn);
        return nextTurn;
    }

    private AgentItemModel appendStatus(
            ExternalActionCommandModel command,
            String resultCode,
            String resultMessage,
            Instant now
    ) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("commandId", command.commandId());
        data.put("runId", command.runId());
        data.put("status", command.status().name());
        data.put("attemptCount", command.attemptCount());
        data.put("retryCycleAttemptCount", command.retryCycleAttemptCount());
        if (resultCode != null && !resultCode.isBlank()) {
            data.put("code", resultCode);
        }
        if (resultMessage != null && !resultMessage.isBlank()) {
            data.put("message", resultMessage);
        }
        return append(new AgentItemModel(UUID.randomUUID().toString(), command.threadId(), command.turnId(), 0,
                AgentItemTypeEnum.EXTERNAL_ACTION_STATUS, writeJson(data), now));
    }

    private AgentItemModel appendTurnState(AgentTurnModel turn, Instant now) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("status", turn.status().name());
        return append(new AgentItemModel(UUID.randomUUID().toString(), turn.threadId(), turn.turnId(), 0,
                AgentItemTypeEnum.TURN_STATE, writeJson(data), now));
    }

    private AgentItemModel append(AgentItemModel item) {
        long sequence = items.appendItem(item);
        return new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), sequence,
                item.type(), item.payload(), item.createdAt());
    }

    private String writeJson(ObjectNode data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码外部动作 Item", failure);
        }
    }

    private static AgentWorkflowStatusEnum workflowStatus(ExternalActionStatusEnum status) {
        return switch (status) {
            case SUCCEEDED -> AgentWorkflowStatusEnum.COMPLETED;
            case MANUAL_RETRY_REQUIRED -> AgentWorkflowStatusEnum.MANUAL_RETRY_REQUIRED;
            case RETRY_WAIT -> AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION;
            default -> throw new IllegalArgumentException("不能投影非终态外部动作：" + status);
        };
    }

    private static AgentTurnStatusEnum turnStatus(ExternalActionStatusEnum status) {
        return switch (status) {
            case SUCCEEDED -> AgentTurnStatusEnum.COMPLETED;
            case MANUAL_RETRY_REQUIRED -> AgentTurnStatusEnum.FAILED;
            case RETRY_WAIT -> AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION;
            default -> throw new IllegalArgumentException("不能投影非终态外部动作：" + status);
        };
    }

    private static boolean isTerminal(AgentWorkflowStatusEnum status) {
        return status == AgentWorkflowStatusEnum.COMPLETED
                || status == AgentWorkflowStatusEnum.REJECTED
                || status == AgentWorkflowStatusEnum.FAILED
                || status == AgentWorkflowStatusEnum.MANUAL_RETRY_REQUIRED;
    }

    private static boolean isTerminal(AgentTurnStatusEnum status) {
        return status == AgentTurnStatusEnum.COMPLETED
                || status == AgentTurnStatusEnum.FAILED
                || status == AgentTurnStatusEnum.CANCELLED
                || status == AgentTurnStatusEnum.TIMED_OUT;
    }

    public record Projection(
            ExternalActionCommandModel command,
            AgentTurnModel turn,
            List<AgentItemModel> items
    ) {
        public Projection {
            items = List.copyOf(items);
        }
    }
}
