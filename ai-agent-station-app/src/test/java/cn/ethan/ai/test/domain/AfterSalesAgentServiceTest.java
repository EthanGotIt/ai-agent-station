package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.exception.AfterSalesResumeConflictException;
import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundResult;
import cn.ethan.ai.domain.agent.model.AfterSalesResumeCommand;
import cn.ethan.ai.domain.agent.model.AfterSalesRunCommand;
import cn.ethan.ai.domain.agent.model.AfterSalesRunResult;
import cn.ethan.ai.domain.agent.model.plan.ChecklistItem;
import cn.ethan.ai.domain.agent.model.plan.PlannedStep;
import cn.ethan.ai.domain.agent.model.plan.PlanningContext;
import cn.ethan.ai.domain.agent.model.plan.RefundPlan;
import cn.ethan.ai.types.common.id.StepId;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesStateMachine;
import cn.ethan.ai.domain.agent.service.AfterSalesAgentService;
import cn.ethan.ai.domain.agent.service.AfterSalesAuditService;
import cn.ethan.ai.domain.agent.policy.RefundInformationGatheringPolicy;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
import cn.ethan.ai.infrastructure.adapter.statemachine.SpringStateMachineAdapter;
import cn.ethan.ai.test.fixture.InMemoryAfterSalesRepository;
import cn.ethan.ai.test.fixture.InMemoryCheckpointRepository;
import cn.ethan.ai.test.fixture.StubAfterSalesToolPort;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class AfterSalesAgentServiceTest {

    private InMemoryAfterSalesRepository repository;
    private InMemoryCheckpointRepository checkpointRepository;
    private AfterSalesAgentService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAfterSalesRepository();
        checkpointRepository = new InMemoryCheckpointRepository();
        repository.orders.put("ORDER-1", new AfterSalesOrderSnapshot("ORDER-1", "user-1", "PAID", null));
        repository.orders.put("ORDER-2", new AfterSalesOrderSnapshot("ORDER-2", "user-2", "PAID", null));
        IAfterSalesStateMachine stateMachine = new SpringStateMachineAdapter(
                new StubAfterSalesToolPort(repository),
                repository,
                new RefundPlanningAgent(null),
                new RefundInformationGatheringPolicy(),
                null,
                checkpointRepository);
        service = new AfterSalesAgentService(stateMachine, repository, new AfterSalesAuditService(null));
    }

    @Test
    void shouldPauseForApprovalThenExecuteExactlyOneRefund() {
        AfterSalesRunResult waiting = service.start(AfterSalesRunCommand.of(
                "user-1", "session-1", "退款订单 ORDER-1", "ORDER-1", "DAMAGED"
        ));

        Assertions.assertEquals(AfterSalesStage.PENDING_APPROVAL.name(), waiting.stage());
        Assertions.assertNotNull(waiting.checkpointId());
        Assertions.assertEquals("REFUND_APPROVAL_REQUIRED", waiting.waitingReason());
        Assertions.assertNotNull(waiting.state().get(AfterSalesAgentState.CHECKLIST));
        Assertions.assertFalse(((java.util.List<?>) waiting.state().get(AfterSalesAgentState.CHECKLIST)).isEmpty());

        AfterSalesRunResult completed = service.resume(AfterSalesResumeCommand.of(
                waiting.caseIdValue(), waiting.checkpointId(), AfterSalesResumeCommand.ResumeAction.APPROVE,
                null, null, "approver-1", "AFTER_SALES_APPROVER"
        ));

        Assertions.assertEquals(AfterSalesStage.COMPLETED.name(), completed.stage());
        Assertions.assertEquals("REFUND_VERIFIED", completed.terminalReason());
        Assertions.assertEquals(1, repository.refundExecutions.get());
        Assertions.assertEquals("REFUNDED", repository.orders.get("ORDER-1").status());

        Assertions.assertThrows(AfterSalesResumeConflictException.class, () -> service.resume(
                AfterSalesResumeCommand.of(waiting.caseIdValue(), waiting.checkpointId(),
                        AfterSalesResumeCommand.ResumeAction.APPROVE, null, null,
                        "approver-1", "AFTER_SALES_APPROVER")
        ));
        Assertions.assertEquals(1, repository.refundExecutions.get());
    }

    @Test
    void shouldRejectStaleCheckpointBeforeSideEffect() {
        AfterSalesRunResult waiting = service.start(AfterSalesRunCommand.of(
                "user-1", "session-1", "退款订单 ORDER-1", "ORDER-1", "DAMAGED"
        ));

        Assertions.assertThrows(AfterSalesResumeConflictException.class, () -> service.resume(
                AfterSalesResumeCommand.of(waiting.caseIdValue(), UUID.randomUUID().toString(),
                        AfterSalesResumeCommand.ResumeAction.APPROVE, null, null,
                        "approver-1", "AFTER_SALES_APPROVER")
        ));
        Assertions.assertEquals(0, repository.refundExecutions.get());
    }

    @Test
    void shouldResumeMissingOrderInformationIntoApproval() {
        AfterSalesRunResult missing = service.start(AfterSalesRunCommand.of(
                "user-1", "session-1", "我要退款", null, "DAMAGED"
        ));
        Assertions.assertEquals(AfterSalesStage.INTAKE.name(), missing.stage());

        AfterSalesRunResult waiting = service.resume(AfterSalesResumeCommand.of(
                missing.caseIdValue(), missing.checkpointId(), AfterSalesResumeCommand.ResumeAction.SUPPLY_INFO,
                "ORDER-1", null, "user-1", "CUSTOMER"
        ));

        Assertions.assertEquals(AfterSalesStage.PENDING_APPROVAL.name(), waiting.stage());
        Assertions.assertNotEquals(missing.checkpointId(), waiting.checkpointId());
    }

    @Test
    void shouldRejectForeignOrderBeforeApproval() {
        AfterSalesRunResult result = service.start(AfterSalesRunCommand.of(
                "user-1", "session-1", "退款订单 ORDER-2", "ORDER-2", "DAMAGED"
        ));

        Assertions.assertEquals(AfterSalesStage.REJECTED.name(), result.stage());
        Assertions.assertEquals("ORDER_NOT_OWNED", result.terminalReason());
        Assertions.assertEquals(0, repository.refundExecutions.get());
    }

    @Test
    void shouldEnforceOwnerAndApproverIdentityBoundaries() {
        AfterSalesRunResult waiting = service.start(AfterSalesRunCommand.of(
                "user-1", "session-1", "退款订单 ORDER-1", "ORDER-1", "DAMAGED"
        ));

        Assertions.assertThrows(SecurityException.class, () -> service.resume(AfterSalesResumeCommand.of(
                waiting.caseIdValue(), waiting.checkpointId(), AfterSalesResumeCommand.ResumeAction.APPROVE,
                null, null, "user-1", "CUSTOMER"
        )));
        Assertions.assertThrows(SecurityException.class,
                () -> service.query(waiting.caseIdValue(), "user-2", "CUSTOMER"));
        Assertions.assertEquals(0, repository.refundExecutions.get());
    }

    @Test
    void shouldAllowOnlyOneConcurrentResumeForSameCheckpoint() throws Exception {
        AfterSalesRunResult waiting = service.start(AfterSalesRunCommand.of(
                "user-1", "session-1", "退款订单 ORDER-1", "ORDER-1", "DAMAGED"
        ));
        AfterSalesResumeCommand command = AfterSalesResumeCommand.of(
                waiting.caseIdValue(), waiting.checkpointId(), AfterSalesResumeCommand.ResumeAction.APPROVE,
                null, null, "approver-1", "AFTER_SALES_APPROVER");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> invokeAfter(start, command));
            Future<Object> second = executor.submit(() -> invokeAfter(start, command));
            start.countDown();
            Object firstResult = first.get();
            Object secondResult = second.get();
            long successes = java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(AfterSalesRunResult.class::isInstance)
                    .count();
            long conflicts = java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(AfterSalesResumeConflictException.class::isInstance)
                    .count();
            Assertions.assertEquals(1, successes);
            Assertions.assertEquals(1, conflicts);
            Assertions.assertEquals(1, repository.refundExecutions.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private Object invokeAfter(CountDownLatch start, AfterSalesResumeCommand command) {
        try {
            start.await();
            return service.resume(command);
        } catch (Exception error) {
            return error;
        }
    }

    @Test
    void repositoryShouldReturnSameCommandForDuplicateIdempotencyKey() {
        AfterSalesRefundResult first = repository.executeRefund("case-1", "ORDER-1", "user-1", "case-1:REFUND");
        AfterSalesRefundResult replay = repository.executeRefund("case-1", "ORDER-1", "user-1", "case-1:REFUND");

        Assertions.assertTrue(first.success());
        Assertions.assertFalse(first.idempotentReplay());
        Assertions.assertTrue(replay.idempotentReplay());
        Assertions.assertEquals(first.commandId(), replay.commandId());
        Assertions.assertEquals(1, repository.refundExecutions.get());
    }

    @Test
    void shouldSucceedAfterOneRePlan() {
        RefundPlan firstPlan = new RefundPlan(false, List.of(
                new PlannedStep(StepId.of("query-order-missing"), "TOOL_CALL", "orderStatus", "query_order",
                        Map.of("orderId", "ORDER-MISSING"), null)
        ), defaultChecklist());
        RefundPlan secondPlan = new RefundPlan(false, List.of(
                new PlannedStep(StepId.of("query-order-1"), "TOOL_CALL", "orderStatus", "query_order",
                        Map.of("orderId", "ORDER-1"), null)
        ), defaultChecklist());

        IAfterSalesStateMachine stateMachine = new SpringStateMachineAdapter(
                new StubAfterSalesToolPort(repository),
                repository,
                new ReplanningAgent(firstPlan, secondPlan),
                new RefundInformationGatheringPolicy(),
                null,
                checkpointRepository);
        service = new AfterSalesAgentService(stateMachine, repository, new AfterSalesAuditService(null));

        AfterSalesRunResult waiting = service.start(AfterSalesRunCommand.of(
                "user-1", "session-1", "退款", "ORDER-MISSING", "DAMAGED"
        ));

        Assertions.assertEquals(AfterSalesStage.PENDING_APPROVAL.name(), waiting.stage());
        Assertions.assertEquals("REFUND_APPROVAL_REQUIRED", waiting.waitingReason());
        Assertions.assertEquals(1, waiting.state().get(AfterSalesAgentState.REPLAN_COUNT));
    }

    private List<ChecklistItem> defaultChecklist() {
        return List.of(
                new ChecklistItem("userId", "DONE"),
                new ChecklistItem("orderId", "PENDING"),
                new ChecklistItem("orderStatus", "PENDING"),
                new ChecklistItem("refundReason", "DONE")
        );
    }

    private static final class ReplanningAgent extends RefundPlanningAgent {
        private final RefundPlan first;
        private final RefundPlan second;
        private int calls;

        ReplanningAgent(RefundPlan first, RefundPlan second) {
            super(null);
            this.first = first;
            this.second = second;
        }

        @Override
        public RefundPlan plan(PlanningContext context) {
            calls++;
            return calls == 1 ? first : second;
        }
    }
}
