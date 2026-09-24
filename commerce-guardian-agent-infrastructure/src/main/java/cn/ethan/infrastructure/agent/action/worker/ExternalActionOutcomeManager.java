package cn.ethan.infrastructure.agent.action.worker;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemJournal;
import cn.ethan.core.agent.thread.AgentItemPayloadCodec;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.workflow.AgentWorkflowOrchestrationVersionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.coordination.AgentContinuationGateway;
import cn.ethan.core.agent.execution.AgentTurnItemPayloads;
import cn.ethan.core.commerce.order.LogisticsEventModel;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.infrastructure.agent.workflow.transaction.OrderWorkflowStepProjection;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
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
    private final AgentItemJournal itemJournal;
    private final AgentItemPayloadCodec itemPayloadCodec;
    private final TransactionTemplate transactionTemplate;
    private final AgentContinuationGateway continuationGateway;

    /** Worker 是否应由本地 Journal 负责本次投影的提交后事件。 */
    public boolean eventsHandledByJournal() {
        return itemJournal != null;
    }

    @Autowired
    public ExternalActionOutcomeManager(
            ExternalActionCommandStore commands,
            AgentItemStore items,
            AgentTurnStore turns,
            AgentWorkflowRunStore workflowRuns,
            ObjectMapper objectMapper,
            AgentItemJournal itemJournal,
            AgentItemPayloadCodec itemPayloadCodec,
            PlatformTransactionManager transactionManager,
            AgentContinuationGateway continuationGateway
    ) {
        this(commands, items, turns, workflowRuns, objectMapper,
                itemJournal,
                itemPayloadCodec,
                transactionManager == null ? null : new TransactionTemplate(transactionManager),
                continuationGateway);
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
        this(commands, items, turns, workflowRuns, objectMapper, null, null, (TransactionTemplate) null,
                null);
    }

    /** 保留既有事务测试和非 Spring 调用边界；续跑由生产装配显式开启。 */
    public ExternalActionOutcomeManager(
            ExternalActionCommandStore commands,
            AgentItemStore items,
            AgentTurnStore turns,
            AgentWorkflowRunStore workflowRuns,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this(commands, items, turns, workflowRuns, objectMapper,
                null,
                null,
                transactionManager == null ? null : new TransactionTemplate(transactionManager),
                null);
    }

    private ExternalActionOutcomeManager(
            ExternalActionCommandStore commands,
            AgentItemStore items,
            AgentTurnStore turns,
            AgentWorkflowRunStore workflowRuns,
            ObjectMapper objectMapper,
            AgentItemJournal itemJournal,
            AgentItemPayloadCodec itemPayloadCodec,
            TransactionTemplate transactionTemplate,
            AgentContinuationGateway continuationGateway
    ) {
        this.commands = commands;
        this.items = items;
        this.turns = turns;
        this.workflowRuns = workflowRuns;
        this.objectMapper = objectMapper;
        this.itemJournal = itemJournal;
        this.itemPayloadCodec = itemPayloadCodec;
        this.transactionTemplate = transactionTemplate;
        this.continuationGateway = continuationGateway;
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
        return transition(expected, next, resultCode, resultMessage, clock, null);
    }

    /**
     * 在外部动作完成后的本地收敛阶段追加可选的最新业务事实；verification 的远程查询必须在调用方事务外完成。
     */
    public Projection transition(
            ExternalActionCommandModel expected,
            ExternalActionCommandModel next,
            String resultCode,
            String resultMessage,
            Clock clock,
            Verification verification
    ) {
        Instant now = clock.instant();
        if (transactionTemplate == null) {
            return project(expected, next, resultCode, resultMessage, now, verification);
        }
        return transactionTemplate.execute(status -> project(expected, next, resultCode, resultMessage, now, verification));
    }

    private Projection project(
            ExternalActionCommandModel expected,
            ExternalActionCommandModel next,
            String resultCode,
            String resultMessage,
            Instant now,
            Verification verification
    ) {
        if (!commands.update(expected, next)) {
            return null;
        }

        AgentWorkflowRunModel run = workflowRuns.find(next.userId(), next.runId())
                .orElseThrow(() -> new IllegalStateException("外部动作对应的 WorkflowRun 不存在：" + next.runId()));
        AgentWorkflowStatusEnum targetWorkflowStatus = workflowStatus(next.status());
        boolean javaWorkflow = run.orchestrationVersion() == AgentWorkflowOrchestrationVersionEnum.EXPEDITE_JAVA_V1;
        if (run.status() != targetWorkflowStatus) {
            if (isImmutableTerminal(run.status())) {
                throw new IllegalStateException("WorkflowRun 已处于冲突终态：" + run.runId());
            }
            workflowRuns.update(run.status(targetWorkflowStatus, progressSteps(next.status(), verification, run.orchestrationVersion()),
                    run.stateJson(), now));
        } else {
            workflowRuns.update(run.progress(progressSteps(next.status(), verification, run.orchestrationVersion()), run.stateJson(), now));
        }

        List<AgentItemModel> projectedItems = new ArrayList<>();
        AgentItemModel statusItem = appendStatus(next, resultCode, resultMessage, now, verification);
        projectedItems.add(statusItem);
        if (javaWorkflow) {
            projectedItems.add(appendWorkflowResult(next, workflowResultStatus(next, verification),
                    workflowResultMessage(next, resultCode, resultMessage, verification), now));
        }
        if (continuationGateway != null && !javaWorkflow) {
            projectedItems.add(appendWorkflowStep(next, "VERIFY_OUTCOME",
                    verification == null ? "WAITING" : verification.verified() ? "COMPLETED" : "ERROR",
                    verification == null ? "UNAVAILABLE" : verification.verified() ? "VERIFIED" : "UNVERIFIED",
                    verification == null ? resultCode : verification.verified() ? null : resultCode,
                    0L, now));
        }
        if (verification != null && verification.order() != null) {
            projectedItems.add(appendOrderDetail(next, verification.order(), now));
            if (verification.logistics() != null) {
                projectedItems.add(appendLogistics(next, verification.order().orderId(), verification.logistics(), now));
            }
        }

        AgentTurnModel projectedTurn = projectTurn(next, now);
        if (projectedTurn != null) {
            projectedItems.add(appendTurnState(projectedTurn, now));
        }
        if (continuationGateway != null && !javaWorkflow) {
            boolean verified = verification != null && verification.verified();
            projectedItems.add(appendWorkflowStep(next, "HANDOFF_AGENT",
                    verified ? "COMPLETED" : "WAITING",
                    verified ? "VERIFIED" : next.status() == ExternalActionStatusEnum.SUCCEEDED
                            ? "PENDING_VERIFICATION" : next.status().name(),
                    verified ? null : resultCode, 0L, now));
        }
        if (continuationGateway != null && !javaWorkflow) {
            projectedItems.addAll(continuationGateway.admit(next, statusItem).items());
        }
        return new Projection(next, projectedTurn, projectedItems);
    }

    private AgentTurnModel projectTurn(ExternalActionCommandModel command, Instant now) {
        if (command.turnId() == null || command.turnId().isBlank()) {
            return null;
        }
        Optional<AgentTurnModel> current = turns.findTurn(command.userId(), command.turnId());
        if (current.isEmpty()) {
            return null;
        }
        AgentTurnModel currentTurn = current.get();
        if (isTerminal(currentTurn.status())) {
            return null;
        }
        AgentTurnStatusEnum target = turnStatus(command.status());
        AgentTurnModel nextTurn = target == AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION
                ? currentTurn.workflow(command.runId(), target)
                : currentTurn.terminal(target,
                target == AgentTurnStatusEnum.FAILED ? "EXTERNAL_ACTION_FAILED" : null, now);
        if (!turns.updateTurn(currentTurn, nextTurn)) {
            throw new AgentThreadConflictException("TURN_VERSION_CONFLICT", "外部动作投影时 Turn 版本竞争：" + nextTurn.turnId());
        }
        return nextTurn;
    }

    private AgentItemModel appendStatus(
            ExternalActionCommandModel command,
            String resultCode,
            String resultMessage,
            Instant now,
            Verification verification
    ) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("commandId", command.commandId());
        data.put("runId", command.runId());
        data.put("status", command.status().name());
        data.put("attemptCount", command.attemptCount());
        data.put("retryCycleAttemptCount", command.retryCycleAttemptCount());
        data.put("maxAttempts", command.maxAttempts());
        data.put("actionType", command.type().name());
        String orderId = orderId(command.payloadJson());
        if (orderId != null) {
            data.put("orderId", orderId);
        }
        if (command.nextAttemptAt() != null) {
            data.put("nextAttemptAt", command.nextAttemptAt().toString());
        }
        if (resultCode != null && !resultCode.isBlank()) {
            data.put("code", resultCode);
        }
        if (resultMessage != null && !resultMessage.isBlank()) {
            data.put("message", resultMessage);
        }
        if (verification != null) {
            data.put("verificationStatus", verification.verified() ? "VERIFIED" : "PENDING");
            if (verification.message() != null && !verification.message().isBlank()) {
                data.put("verificationMessage", verification.message());
            }
            if (verification.verifiedAt() != null) {
                data.put("verifiedAt", verification.verifiedAt().toString());
            }
        }
        String legacyPayload = writeJson(data);
        var structuredPayload = AgentTurnItemPayloads.externalActionStatusValue(
                command.commandId(), command.runId(), command.status().name(), command.attemptCount(),
                command.retryCycleAttemptCount(), command.maxAttempts(), command.type().name(), orderId,
                command.nextAttemptAt() == null ? null : command.nextAttemptAt().toString(), resultCode,
                resultMessage, verification == null ? null : verification.verified() ? "VERIFIED" : "PENDING",
                verification == null ? null : verification.message(),
                verification == null || verification.verifiedAt() == null ? null : verification.verifiedAt().toString());
        String payload = itemPayloadCodec == null ? legacyPayload
                : itemPayloadCodec.encode(AgentItemTypeEnum.EXTERNAL_ACTION_STATUS, structuredPayload);
        return append(new AgentItemModel(UUID.randomUUID().toString(), command.threadId(), command.turnId(), 0,
                AgentItemTypeEnum.EXTERNAL_ACTION_STATUS, payload, now));
    }

    private AgentItemModel appendOrderDetail(
            ExternalActionCommandModel command,
            OrderSnapshotModel order,
            Instant now
    ) {
        String legacyPayload = writeJson(safeOrder(order));
        String payload = itemPayloadCodec == null ? legacyPayload
                : itemPayloadCodec.encode(AgentItemTypeEnum.ORDER_DETAIL,
                AgentTurnItemPayloads.orderDetailValue(order));
        return append(new AgentItemModel(UUID.randomUUID().toString(), command.threadId(), command.turnId(), 0,
                AgentItemTypeEnum.ORDER_DETAIL, payload, now));
    }

    private AgentItemModel appendLogistics(
            ExternalActionCommandModel command,
            String orderId,
            List<LogisticsEventModel> events,
            Instant now
    ) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("orderId", orderId);
        var array = data.putArray("events");
        for (LogisticsEventModel event : events) {
            ObjectNode node = array.addObject();
            node.put("eventId", event.eventId());
            node.put("status", event.status());
            node.put("location", event.location());
            node.put("description", event.description());
            node.put("occurredAt", event.occurredAt().toString());
        }
        String legacyPayload = writeJson(data);
        String payload = itemPayloadCodec == null ? legacyPayload
                : itemPayloadCodec.encode(AgentItemTypeEnum.LOGISTICS_TIMELINE,
                AgentTurnItemPayloads.logisticsTimelineValue(orderId, events));
        return append(new AgentItemModel(UUID.randomUUID().toString(), command.threadId(), command.turnId(), 0,
                AgentItemTypeEnum.LOGISTICS_TIMELINE, payload, now));
    }

    private ObjectNode safeOrder(OrderSnapshotModel order) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("orderId", order.orderId());
        data.put("orderStatus", order.status().name());
        if (order.createdAt() != null) data.put("createdAt", order.createdAt().toString());
        if (order.expectedDeliveryAt() != null) data.put("expectedDeliveryAt", order.expectedDeliveryAt().toString());
        if (order.lastLogisticsAt() != null) data.put("lastLogisticsAt", order.lastLogisticsAt().toString());
        if (order.logisticsStatus() != null) data.put("logisticsStatus", order.logisticsStatus());
        if (order.paidAmount() != null) data.put("paidAmount", order.paidAmount());
        if (order.currency() != null) data.put("currency", order.currency());
        if (order.itemSummary() != null) data.put("itemSummary", order.itemSummary());
        data.put("visibility", order.hiddenAt() == null ? "ACTIVE" : "HIDDEN");
        return data;
    }

    private String orderId(String payloadJson) {
        try {
            String value = objectMapper.readTree(payloadJson == null ? "{}" : payloadJson).path("orderId").asString("").strip();
            return value.isBlank() ? null : value;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private AgentItemModel appendTurnState(AgentTurnModel turn, Instant now) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("status", turn.status().name());
        String legacyPayload = writeJson(data);
        String payload = itemPayloadCodec == null ? legacyPayload
                : itemPayloadCodec.encode(AgentItemTypeEnum.TURN_STATE,
                AgentTurnItemPayloads.turnStateValue(turn.status(), null));
        return append(new AgentItemModel(UUID.randomUUID().toString(), turn.threadId(), turn.turnId(), 0,
                AgentItemTypeEnum.TURN_STATE, payload, now));
    }

    private AgentItemModel appendWorkflowStep(
            ExternalActionCommandModel command,
            String node,
            String status,
            String branch,
            String code,
            long elapsedMillis,
            Instant now
    ) {
        var structuredPayload = AgentTurnItemPayloads.workflowStepValue(
                command.runId(), node, status, branch, code, elapsedMillis);
        String legacyPayload = AgentTurnItemPayloads.workflowStep(
                command.runId(), node, status, branch, code, elapsedMillis);
        String payload = itemPayloadCodec == null
                ? legacyPayload : itemPayloadCodec.encode(AgentItemTypeEnum.WORKFLOW_STEP, structuredPayload);
        return append(new AgentItemModel(UUID.randomUUID().toString(), command.threadId(), command.turnId(), 0,
                AgentItemTypeEnum.WORKFLOW_STEP,
                payload, now));
    }

    private String workflowResultStatus(ExternalActionCommandModel command, Verification verification) {
        return switch (command.status()) {
            case SUCCEEDED -> verification != null && verification.verified() ? "COMPLETED" : "APPROVED";
            case RETRY_WAIT -> "WAITING_EXTERNAL_ACTION";
            case MANUAL_RETRY_REQUIRED -> "MANUAL_RETRY_REQUIRED";
            default -> command.status().name();
        };
    }

    private String workflowResultMessage(ExternalActionCommandModel command, String resultCode,
                                         String resultMessage, Verification verification) {
        if (command.status() == ExternalActionStatusEnum.SUCCEEDED && verification != null) {
            return verification.message();
        }
        return resultMessage == null || resultMessage.isBlank() ? resultCode : resultMessage;
    }

    private AgentItemModel appendWorkflowResult(ExternalActionCommandModel command, String status,
                                                String message, Instant now) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("runId", command.runId());
        data.put("status", status);
        if (message != null && !message.isBlank()) data.put("message", message);
        String legacyPayload = writeJson(data);
        var structuredPayload = AgentTurnItemPayloads.workflowResultValue(command.runId(), status, message);
        String payload = itemPayloadCodec == null ? legacyPayload
                : itemPayloadCodec.encode(AgentItemTypeEnum.WORKFLOW_RESULT, structuredPayload);
        return append(new AgentItemModel(UUID.randomUUID().toString(), command.threadId(), command.turnId(), 0,
                AgentItemTypeEnum.WORKFLOW_RESULT, payload, now));
    }

    private AgentItemModel append(AgentItemModel item) {
        AgentItemModel encoded = itemPayloadCodec == null ? item : new AgentItemModel(
                item.itemId(), item.threadId(), item.turnId(), item.sequence(), item.type(),
                itemPayloadCodec.encodeJsonText(item.type(), item.payload()), item.createdAt());
        if (itemJournal != null) {
            return itemJournal.append(encoded);
        }
        long sequence = items.appendItem(encoded);
        return new AgentItemModel(encoded.itemId(), encoded.threadId(), encoded.turnId(), sequence,
                encoded.type(), encoded.payload(), encoded.createdAt());
    }

    private String writeJson(ObjectNode data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码外部动作 Item", failure);
        }
    }

    private String progressSteps(ExternalActionStatusEnum status, Verification verification,
                                 AgentWorkflowOrchestrationVersionEnum orchestrationVersion) {
        boolean javaWorkflow = orchestrationVersion == AgentWorkflowOrchestrationVersionEnum.EXPEDITE_JAVA_V1;
        boolean versionedExpedite = javaWorkflow
                || orchestrationVersion == AgentWorkflowOrchestrationVersionEnum.EXPEDITE_GRAPH_V2;
        String executionNode = versionedExpedite ? "HANDOFF_WORKER" : "EXECUTE_ACTION";
        if (status == ExternalActionStatusEnum.RETRY_WAIT) {
            return OrderWorkflowStepProjection.snapshot(objectMapper, executionNode, "WAITING", orchestrationVersion);
        }
        if (status == ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED) {
            return OrderWorkflowStepProjection.snapshot(objectMapper, executionNode, "ERROR", orchestrationVersion);
        }
        if (status == ExternalActionStatusEnum.SUCCEEDED) {
            if (javaWorkflow) {
                return OrderWorkflowStepProjection.snapshot(objectMapper, "VERIFY_OUTCOME",
                        verification != null && verification.verified() ? "COMPLETED" : "ERROR", orchestrationVersion);
            }
            if (orchestrationVersion == AgentWorkflowOrchestrationVersionEnum.EXPEDITE_GRAPH_V2) {
                return OrderWorkflowStepProjection.snapshot(objectMapper, executionNode,
                        verification != null && verification.verified() ? "COMPLETED" : "ERROR", orchestrationVersion);
            }
            return verification != null && verification.verified()
                    ? OrderWorkflowStepProjection.snapshot(objectMapper, "HANDOFF_AGENT", "COMPLETED", orchestrationVersion)
                    : OrderWorkflowStepProjection.snapshot(objectMapper, "VERIFY_OUTCOME", "ERROR", orchestrationVersion);
        }
        return OrderWorkflowStepProjection.snapshot(objectMapper, executionNode, "ACTIVE", orchestrationVersion);
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

    private static boolean isImmutableTerminal(AgentWorkflowStatusEnum status) {
        return status == AgentWorkflowStatusEnum.COMPLETED
                || status == AgentWorkflowStatusEnum.REJECTED
                || status == AgentWorkflowStatusEnum.FAILED;
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

    /** 外部动作已完成后的只读核验结果；不承载敏感身份信息。 */
    public record Verification(
            boolean verified,
            String message,
            Instant verifiedAt,
            OrderSnapshotModel order,
            List<LogisticsEventModel> logistics
    ) {
        public Verification {
            logistics = logistics == null ? List.of() : List.copyOf(logistics);
        }

        public static Verification unavailable(String message, Instant at) {
            return new Verification(false, message, at, null, List.of());
        }

        public static Verification found(OrderSnapshotModel order, List<LogisticsEventModel> logistics, Instant at) {
            return fromFacts(order, logistics, true, "最新订单状态已核验", at);
        }

        public static Verification fromFacts(
                OrderSnapshotModel order,
                List<LogisticsEventModel> logistics,
                boolean verified,
                String message,
                Instant at
        ) {
            return new Verification(verified, message, at, order, logistics);
        }
    }
}
