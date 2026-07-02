package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundResult;
import cn.ethan.ai.domain.agent.model.AfterSalesResumeCommand;
import cn.ethan.ai.domain.agent.model.AfterSalesRunCommand;
import cn.ethan.ai.domain.agent.model.AfterSalesRunResult;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.service.AfterSalesAgentService;
import cn.ethan.ai.domain.agent.service.AfterSalesGraphRuntime;
import cn.ethan.ai.domain.agent.service.exception.AfterSalesResumeConflictException;
import cn.ethan.ai.domain.agent.adapter.repository.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.adapter.port.IAfterSalesToolPort;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AfterSalesAgentServiceTest {

    private InMemoryAfterSalesRepository repository;
    private AfterSalesAgentService service;

    @BeforeEach
    void setUp() throws Exception {
        repository = new InMemoryAfterSalesRepository();
        repository.orders.put("ORDER-1", new AfterSalesOrderSnapshot("ORDER-1", "user-1", "PAID", null));
        repository.orders.put("ORDER-2", new AfterSalesOrderSnapshot("ORDER-2", "user-2", "PAID", null));
        AfterSalesGraphRuntime graphRuntime = new AfterSalesGraphRuntime(
                new MemorySaver(), new StubToolPort(repository), repository);
        service = new AfterSalesAgentService(graphRuntime, repository);
    }

    @Test
    void shouldPauseForApprovalThenExecuteExactlyOneRefund() {
        AfterSalesRunResult waiting = service.start(new AfterSalesRunCommand(
                "user-1", "session-1", "退款订单 ORDER-1", "ORDER-1", "DAMAGED"
        ));

        Assertions.assertEquals(AfterSalesStage.READY_FOR_APPROVAL.name(), waiting.stage());
        Assertions.assertNotNull(waiting.checkpointId());
        Assertions.assertEquals("REFUND_APPROVAL_REQUIRED", waiting.waitingReason());

        AfterSalesRunResult completed = service.resume(new AfterSalesResumeCommand(
                waiting.runId(), waiting.checkpointId(), AfterSalesResumeCommand.ResumeAction.APPROVE,
                null, null
        ));

        Assertions.assertEquals(AfterSalesStage.COMPLETED.name(), completed.stage());
        Assertions.assertEquals("REFUND_VERIFIED", completed.terminalReason());
        Assertions.assertEquals(1, repository.refundExecutions.get());
        Assertions.assertEquals("REFUNDED", repository.orders.get("ORDER-1").status());

        Assertions.assertThrows(AfterSalesResumeConflictException.class, () -> service.resume(
                new AfterSalesResumeCommand(waiting.runId(), waiting.checkpointId(),
                        AfterSalesResumeCommand.ResumeAction.APPROVE, null, null)
        ));
        Assertions.assertEquals(1, repository.refundExecutions.get());
    }

    @Test
    void shouldRejectStaleCheckpointBeforeSideEffect() {
        AfterSalesRunResult waiting = service.start(new AfterSalesRunCommand(
                "user-1", "session-1", "退款订单 ORDER-1", "ORDER-1", "DAMAGED"
        ));

        Assertions.assertThrows(AfterSalesResumeConflictException.class, () -> service.resume(
                new AfterSalesResumeCommand(waiting.runId(), UUID.randomUUID().toString(),
                        AfterSalesResumeCommand.ResumeAction.APPROVE, null, null)
        ));
        Assertions.assertEquals(0, repository.refundExecutions.get());
    }

    @Test
    void shouldResumeMissingOrderInformationIntoApproval() {
        AfterSalesRunResult missing = service.start(new AfterSalesRunCommand(
                "user-1", "session-1", "我要退款", null, "DAMAGED"
        ));
        Assertions.assertEquals(AfterSalesStage.NEED_USER_INPUT.name(), missing.stage());

        AfterSalesRunResult waiting = service.resume(new AfterSalesResumeCommand(
                missing.runId(), missing.checkpointId(), AfterSalesResumeCommand.ResumeAction.SUPPLY_INFO,
                "ORDER-1", null
        ));

        Assertions.assertEquals(AfterSalesStage.READY_FOR_APPROVAL.name(), waiting.stage());
        Assertions.assertNotEquals(missing.checkpointId(), waiting.checkpointId());
    }

    @Test
    void shouldRejectForeignOrderBeforeApproval() {
        AfterSalesRunResult result = service.start(new AfterSalesRunCommand(
                "user-1", "session-1", "退款订单 ORDER-2", "ORDER-2", "DAMAGED"
        ));

        Assertions.assertEquals(AfterSalesStage.REJECTED.name(), result.stage());
        Assertions.assertEquals("ORDER_NOT_OWNED", result.terminalReason());
        Assertions.assertEquals(0, repository.refundExecutions.get());
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

    private static final class StubToolPort implements IAfterSalesToolPort {
        private final InMemoryAfterSalesRepository repository;

        private StubToolPort(InMemoryAfterSalesRepository repository) {
            this.repository = repository;
        }

        @Override
        public AfterSalesToolRequest proposeOrderQuery(String userMessage, String userId, String orderIdHint,
                                                       String refundReason, String correction) {
            if (orderIdHint == null || orderIdHint.isBlank()) {
                throw new IllegalArgumentException("ORDER_ID_REQUIRED");
            }
            return new AfterSalesToolRequest("call-1", "query_order",
                    JSON.toJSONString(Map.of("orderId", orderIdHint)));
        }

        @Override
        public AfterSalesToolResult executeOrderQuery(AfterSalesToolRequest request,
                                                      String userId,
                                                      String userMessage) {
            JSONObject arguments = JSON.parseObject(request.argumentsJson());
            AfterSalesOrderSnapshot order = repository.orders.get(arguments.getString("orderId"));
            if (order == null) {
                return AfterSalesToolResult.failure("", "ORDER_NOT_FOUND", "订单不存在");
            }
            String ownerId = userId.equals(order.ownerId()) ? userId : "__FOREIGN__";
            AfterSalesOrderSnapshot sanitized = new AfterSalesOrderSnapshot(
                    order.orderId(), ownerId, order.status(), order.daysSinceDelivery());
            return AfterSalesToolResult.success("{}", sanitized);
        }
    }

    private static final class InMemoryAfterSalesRepository implements IAfterSalesRepository {
        private final Map<String, AfterSalesOrderSnapshot> orders = new ConcurrentHashMap<>();
        private final Map<String, AfterSalesCaseView> cases = new ConcurrentHashMap<>();
        private final Map<String, AfterSalesRefundResult> commands = new ConcurrentHashMap<>();
        private final AtomicInteger refundExecutions = new AtomicInteger();

        @Override
        public Optional<AfterSalesOrderSnapshot> findOrder(String orderId) {
            return Optional.ofNullable(orders.get(orderId));
        }

        @Override
        public void createCase(String runId, String caseId, String userId, String sessionId, String message) {
            cases.put(runId, new AfterSalesCaseView(runId, caseId, userId, sessionId,
                    null, AfterSalesStage.INTAKE.name(), null, null, null, null));
        }

        @Override
        public void updateCase(AfterSalesCaseView caseView) {
            cases.put(caseView.runId(), caseView);
        }

        @Override
        public Optional<AfterSalesCaseView> findCase(String runId) {
            return Optional.ofNullable(cases.get(runId));
        }

        @Override
        public boolean cancelCase(String runId, String reason) {
            return cases.containsKey(runId);
        }

        @Override
        public synchronized AfterSalesRefundResult executeRefund(String caseId, String orderId,
                                                                 String userId, String idempotencyKey) {
            AfterSalesRefundResult existing = commands.get(idempotencyKey);
            if (existing != null) {
                return new AfterSalesRefundResult(true, true, existing.commandId(), "ALREADY_EXECUTED");
            }
            AfterSalesOrderSnapshot order = orders.get(orderId);
            if (order == null || !userId.equals(order.ownerId())) {
                return new AfterSalesRefundResult(false, false, null, "ORDER_STATE_CONFLICT");
            }
            String commandId = UUID.randomUUID().toString();
            refundExecutions.incrementAndGet();
            orders.put(orderId, new AfterSalesOrderSnapshot(orderId, userId, "REFUNDED",
                    order.daysSinceDelivery()));
            AfterSalesRefundResult result = new AfterSalesRefundResult(
                    true, false, commandId, "REFUND_EXECUTED");
            commands.put(idempotencyKey, result);
            return result;
        }
    }
}
