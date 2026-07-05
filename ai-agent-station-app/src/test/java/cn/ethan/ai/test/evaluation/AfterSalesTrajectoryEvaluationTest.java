package cn.ethan.ai.test.evaluation;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundResult;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.policy.AfterSalesToolContractValidator;
import cn.ethan.ai.domain.agent.policy.RefundInformationGatheringPolicy;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
import cn.ethan.ai.infrastructure.adapter.statemachine.SpringStateMachineAdapter;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public class AfterSalesTrajectoryEvaluationTest {

    private SpringStateMachineAdapter stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new SpringStateMachineAdapter(
                new TrajectoryToolPort(),
                new InMemoryRepository(),
                new RefundPlanningAgent(null),
                new RefundInformationGatheringPolicy(),
                null);
    }

    @Test
    void frozenThirtyCaseSetShouldKeepGovernedRoutesDeterministic() throws Exception {
        List<JSONObject> cases = loadCases();
        Assertions.assertEquals(30, cases.size());
        Assertions.assertEquals(12, categoryCount(cases, "NORMAL"));
        Assertions.assertEquals(8, categoryCount(cases, "RULE"));
        Assertions.assertEquals(10, categoryCount(cases, "ERROR"));

        List<String> failures = new ArrayList<>();
        for (JSONObject testCase : cases) {
            AfterSalesAgentState state = stateMachine.execute(toInput(testCase), testCase.getString("id"));
            String expected = testCase.getString("expectedStage");
            if (!expected.equals(state.stage().name())) {
                failures.add(testCase.getString("id") + ": expected=" + expected + ", actual=" + state.stage());
            }
        }
        Assertions.assertTrue(failures.isEmpty(), String.join("; ", failures));
    }

    private List<JSONObject> loadCases() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/evaluation/after-sales-trajectory-v1.jsonl");
        Assertions.assertNotNull(stream);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> !line.isBlank())
                    .map(JSON::parseObject)
                    .toList();
        }
    }

    private long categoryCount(List<JSONObject> cases, String category) {
        return cases.stream().filter(item -> category.equals(item.getString("category"))).count();
    }

    private Map<String, Object> toInput(JSONObject testCase) {
        Map<String, Object> input = new LinkedHashMap<>();
        String userId = testCase.getString("userId");
        putText(input, AfterSalesAgentState.USER_ID, userId);
        putText(input, AfterSalesAgentState.SESSION_ID, userId != null ? "session-" + userId : null);
        putText(input, AfterSalesAgentState.ORDER_ID, testCase.getString("orderId"));
        putText(input, AfterSalesAgentState.ORDER_OWNER_ID, testCase.getString("ownerId"));
        putText(input, AfterSalesAgentState.ORDER_STATUS, testCase.getString("status"));
        String reason = testCase.getString("reason");
        String errorType = testCase.getString("errorType");
        // Error cases reuse errorType as refundReason/userMessage so the stub tool port can inject failures.
        putText(input, AfterSalesAgentState.REFUND_REASON, reason != null ? reason : errorType);
        putText(input, AfterSalesAgentState.USER_MESSAGE, errorType);
        putText(input, AfterSalesAgentState.ERROR_TYPE, errorType);
        putNumber(testCase, "days", value -> input.put(AfterSalesAgentState.DAYS_SINCE_DELIVERY, value));
        putNumber(testCase, "repairCount", value -> input.put(AfterSalesAgentState.REPAIR_COUNT, value));
        putNumber(testCase, "retryCount", value -> input.put(AfterSalesAgentState.RETRY_COUNT, value));
        putNumber(testCase, "reloadCount", value -> input.put(AfterSalesAgentState.RELOAD_COUNT, value));
        if (testCase.containsKey("sameFailureRepeated")) {
            input.put(AfterSalesAgentState.SAME_FAILURE_REPEATED,
                    testCase.getBooleanValue("sameFailureRepeated"));
        }
        return input;
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private void putNumber(JSONObject source, String key, Consumer<Integer> consumer) {
        if (source.containsKey(key)) {
            consumer.accept(source.getInteger(key));
        }
    }

    private static final class TrajectoryToolPort implements IAfterSalesToolPort {

        @Override
        public AfterSalesToolRequest proposeOrderQuery(String userMessage,
                                                       String userId,
                                                       String sessionId,
                                                       String orderIdHint,
                                                       String refundReason,
                                                       String correction) {
            if ("ARGUMENT_INVALID".equalsIgnoreCase(refundReason)) {
                // Produce an invalid tool request to exercise argument repair/recovery.
                return new AfterSalesToolRequest(UUID.randomUUID().toString(),
                        AfterSalesToolContractValidator.QUERY_ORDER_TOOL, "{}");
            }
            String orderId = orderIdHint != null && !orderIdHint.isBlank()
                    ? orderIdHint : "dummy-order";
            return new AfterSalesToolRequest(UUID.randomUUID().toString(),
                    AfterSalesToolContractValidator.QUERY_ORDER_TOOL,
                    JSON.toJSONString(Map.of("orderId", orderId)));
        }

        @Override
        public AfterSalesToolResult executeOrderQuery(AfterSalesToolRequest request,
                                                      String userId,
                                                      String userMessage) {
            String errorType = userMessage == null ? "" : userMessage.toUpperCase();
            if (errorType.isBlank() || "ARGUMENT_INVALID".equals(errorType)) {
                JSONObject arguments = JSON.parseObject(request.argumentsJson());
                String orderId = arguments == null ? "dummy-order" : arguments.getString("orderId");
                return AfterSalesToolResult.success("{}",
                        new AfterSalesOrderSnapshot(orderId, userId, "PAID", null));
            }
            return AfterSalesToolResult.failure("", errorType, "injected " + errorType);
        }
    }

    private static final class InMemoryRepository implements IAfterSalesRepository {

        @Override
        public Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId) {
            return Optional.empty();
        }

        @Override
        public void createCase(String caseId, String userId, String sessionId, String message) {
        }

        @Override
        public void updateCase(AfterSalesCaseView caseView) {
        }

        @Override
        public Optional<AfterSalesCaseView> findCase(String caseId) {
            return Optional.empty();
        }

        @Override
        public boolean cancelCase(String caseId, String reason) {
            return false;
        }

        @Override
        public AfterSalesRefundResult executeRefund(String caseId, String orderId,
                                                    String userId, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }
    }
}
