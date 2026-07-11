package cn.ethan.ai.test.evaluation;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.policy.AfterSalesToolContractValidator;
import cn.ethan.ai.domain.agent.policy.RefundInformationGatheringPolicy;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
import cn.ethan.ai.infrastructure.adapter.statemachine.SpringStateMachineAdapter;
import cn.ethan.ai.infrastructure.json.AfterSalesJsonCodec;
import cn.ethan.ai.test.fixture.InMemoryCheckpointRepository;
import cn.ethan.ai.test.fixture.UnsupportedAfterSalesRepository;
import tools.jackson.core.type.TypeReference;
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
import java.util.UUID;
import java.util.function.Consumer;

public class AfterSalesTrajectoryEvaluationTest {

    private static final AfterSalesJsonCodec JSON = AfterSalesJsonCodec.defaultCodec();
    private SpringStateMachineAdapter stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new SpringStateMachineAdapter(
                new TrajectoryToolPort(),
                new InMemoryRepository(),
                new RefundPlanningAgent(null),
                new RefundInformationGatheringPolicy(),
                null,
                new InMemoryCheckpointRepository());
    }

    @Test
    void frozenThirtyCaseSetShouldKeepGovernedRoutesDeterministic() throws Exception {
        List<Map<String, Object>> cases = loadCases();
        Assertions.assertEquals(30, cases.size());
        Assertions.assertEquals(12, categoryCount(cases, "NORMAL"));
        Assertions.assertEquals(8, categoryCount(cases, "RULE"));
        Assertions.assertEquals(10, categoryCount(cases, "ERROR"));

        List<String> failures = new ArrayList<>();
        for (Map<String, Object> testCase : cases) {
            AfterSalesAgentState state = stateMachine.execute(
                    toInput(testCase), text(testCase, "id")).state();
            String expected = text(testCase, "expectedStage");
            if (!expected.equals(state.stage().name())) {
                failures.add(text(testCase, "id") + ": expected=" + expected + ", actual=" + state.stage());
            }
        }
        Assertions.assertTrue(failures.isEmpty(), String.join("; ", failures));
    }

    private List<Map<String, Object>> loadCases() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/evaluation/after-sales-trajectory-v1.jsonl");
        Assertions.assertNotNull(stream);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> JSON.read(line, new TypeReference<Map<String, Object>>() {
                    }, "解析冻结轨迹"))
                    .toList();
        }
    }

    private long categoryCount(List<Map<String, Object>> cases, String category) {
        return cases.stream().filter(item -> category.equals(text(item, "category"))).count();
    }

    private Map<String, Object> toInput(Map<String, Object> testCase) {
        Map<String, Object> input = new LinkedHashMap<>();
        String userId = text(testCase, "userId");
        putText(input, AfterSalesAgentState.USER_ID, userId);
        putText(input, AfterSalesAgentState.SESSION_ID, userId != null ? "session-" + userId : null);
        putText(input, AfterSalesAgentState.ORDER_ID, text(testCase, "orderId"));
        putText(input, AfterSalesAgentState.ORDER_OWNER_ID, text(testCase, "ownerId"));
        putText(input, AfterSalesAgentState.ORDER_STATUS, text(testCase, "status"));
        String reason = text(testCase, "reason");
        String errorType = text(testCase, "errorType");
        // Error cases reuse errorType as refundReason/userMessage so the stub tool port can inject failures.
        putText(input, AfterSalesAgentState.REFUND_REASON, reason != null ? reason : errorType);
        putText(input, AfterSalesAgentState.USER_MESSAGE, errorType);
        putText(input, AfterSalesAgentState.ERROR_TYPE, errorType);
        putNumber(testCase, "days", value -> input.put(AfterSalesAgentState.DAYS_SINCE_DELIVERY, value));
        putNumber(testCase, "repairCount", value -> input.put(AfterSalesAgentState.REPAIR_COUNT, value));
        putNumber(testCase, "retryCount", value -> input.put(AfterSalesAgentState.RETRY_COUNT, value));
        putNumber(testCase, "reloadCount", value -> input.put(AfterSalesAgentState.RELOAD_COUNT, value));
        return input;
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private void putNumber(Map<String, Object> source, String key, Consumer<Integer> consumer) {
        if (source.containsKey(key)) {
            consumer.accept(((Number) source.get(key)).intValue());
        }
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static final class TrajectoryToolPort implements IAfterSalesToolPort {

        @Override
        public AfterSalesToolResult executeReadOnly(AfterSalesToolRequest request,
                                                    cn.ethan.ai.domain.agent.model.AfterSalesToolContext context) {
            Map<String, Object> arguments = JSON.read(request.argumentsJson(), new TypeReference<>() {
            }, "解析轨迹工具参数");
            String orderId = arguments == null ? "dummy-order" : text(arguments, "orderId");
            return AfterSalesToolResult.success("{}", new cn.ethan.ai.domain.agent.model.ToolEvidence("query_order",
                    Map.of("orderId", orderId, "ownerId", context.userId(), "status", "PAID")));
        }
    }

    private static final class InMemoryRepository extends UnsupportedAfterSalesRepository {
    }
}
