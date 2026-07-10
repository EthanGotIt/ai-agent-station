package cn.ethan.ai.test.evaluation;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundResult;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.model.AfterSalesToolContext;
import cn.ethan.ai.domain.agent.model.ToolEvidence;
import cn.ethan.ai.domain.agent.model.plan.PlanningContext;
import cn.ethan.ai.domain.agent.policy.RefundInformationGatheringPolicy;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.domain.agent.port.driven.IOrderGateway;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
import cn.ethan.ai.infrastructure.adapter.ai.SpringAiAfterSalesToolAdapter;
import cn.ethan.ai.infrastructure.adapter.statemachine.SpringStateMachineAdapter;
import cn.ethan.ai.test.fixture.InMemoryCheckpointRepository;
import cn.ethan.ai.test.support.DotenvConditions;
import cn.ethan.ai.test.support.DotenvExtension;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@SpringBootTest(properties = {
        "spring.ai.model.chat=openai"
})
@ActiveProfiles("dev")
@ExtendWith(DotenvExtension.class)
@EnabledIf(value = "cn.ethan.ai.test.support.DotenvConditions#isLiveEvaluationEnabled",
        disabledReason = "实时模型评估需通过 .env 开启")
public class AfterSalesLiveModelEvaluationIT {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ChatModel chatModel;

    @Value("${spring.ai.openai.chat.model:deepseek-v4-pro}")
    private String planningModel;

    @Test
    void shouldEvaluateThirtyFrozenCasesWithRealModel() throws Exception {
        Assertions.assertFalse(applicationContext.getBeansOfType(ChatModel.class).isEmpty(),
                "实时评估必须存在 ChatModel，禁止退化为正则兜底");
        String apiKey = applicationContext.getEnvironment().getProperty("spring.ai.openai.api-key");
        Assertions.assertTrue(apiKey != null && !apiKey.isBlank(), "实时评估未加载本地 API key");
        List<JSONObject> cases = loadCases();
        Assertions.assertEquals(30, cases.size());

        // 实时模型评测只验证模型 Plan 契约，Session Memory 由独立集成路径覆盖。
        ChatClient planningClient = ChatClient.builder(chatModel)
                .defaultOptions(OpenAiChatOptions.builder().model(planningModel).temperature(0.0))
                .build();
        RefundPlanningAgent planningAgent = new RefundPlanningAgent(planningClient);
        List<Long> latencies = new ArrayList<>();
        JSONArray failures = new JSONArray();
        int modelContractPassed = 0;
        int governedRoutePassed = 0;

        for (JSONObject testCase : cases) {
            Instant startedAt = Instant.now();
            String contractFailure = evaluateModelContract(planningAgent, testCase);
            latencies.add(java.time.Duration.between(startedAt, Instant.now()).toMillis());
            if (contractFailure == null) {
                modelContractPassed++;
            } else {
                failures.add(failure(testCase, "MODEL_CONTRACT", contractFailure));
            }

            AfterSalesAgentState state = new SpringStateMachineAdapter(
                    new TrajectoryToolPort(testCase),
                    new TrajectoryRepository(testCase),
                    new RefundPlanningAgent(null),
                    new RefundInformationGatheringPolicy(),
                    null,
                    new InMemoryCheckpointRepository())
                    .execute(toGovernedInput(testCase), "eval-" + testCase.getString("id")).state();
            String expectedStage = testCase.getString("expectedStage");
            if (expectedStage.equals(state.stage().name())) {
                governedRoutePassed++;
            } else {
                failures.add(failure(testCase, "GOVERNED_ROUTE",
                        "expected=" + expectedStage + ",actual=" + state.stage().name()));
            }
        }

        JSONObject report = report(cases.size(), modelContractPassed, governedRoutePassed, latencies, failures);
        Path output = Path.of("target", "evaluation", "after-sales-live-evaluation.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.toJSONString(report, true), StandardCharsets.UTF_8);

        Assertions.assertEquals(30, modelContractPassed, report.toJSONString());
        Assertions.assertEquals(30, governedRoutePassed, report.toJSONString());
    }

    private String evaluateModelContract(RefundPlanningAgent planningAgent, JSONObject testCase) {
        String caseId = testCase.getString("id");
        String expectedOrderId = testCase.getString("orderId");
        boolean expectNoToolCall = "rule-01".equals(caseId);
        if (expectedOrderId == null && !expectNoToolCall) {
            expectedOrderId = "probe-" + caseId;
        }
        final String expectedOrderIdForPlan = expectedOrderId;
        String message = expectNoToolCall ? "我要退款，商品有问题" : "我要退款，订单号 " + expectedOrderId;
        try {
            String userId = Optional.ofNullable(testCase.getString("userId")).orElse("eval-user");
            PlanningContext context = new PlanningContext(
                    "live-" + caseId, userId, "session-" + userId, message,
                    expectNoToolCall ? null : expectedOrderIdForPlan, null,
                    Optional.ofNullable(testCase.getString("reason")).orElse("DAMAGED"),
                    null, null, 0, 0, null, null);
            var plan = planningAgent.plan(context);
            var validation = new RefundInformationGatheringPolicy().validate(plan, context);
            if (!validation.ok()) {
                return validation.errorType();
            }
            if (expectNoToolCall) {
                return plan.steps().stream().anyMatch(step -> "TOOL_CALL".equals(step.action()))
                        ? "missing orderId should not produce a tool call" : null;
            }
            return plan.steps().stream()
                    .filter(step -> "TOOL_CALL".equals(step.action()))
                    .anyMatch(step -> "query_order".equals(step.toolName())
                            && expectedOrderIdForPlan.equals(String.valueOf(step.input().get("orderId"))))
                    ? null : "expected query_order plan";
        } catch (RuntimeException error) {
            return error.getClass().getSimpleName() + ":" + error.getMessage();
        }
    }

    private JSONObject report(int total,
                              int modelContractPassed,
                              int governedRoutePassed,
                              List<Long> latencies,
                              JSONArray failures) {
        List<Long> sorted = latencies.stream().sorted(Comparator.naturalOrder()).toList();
        long sum = latencies.stream().mapToLong(Long::longValue).sum();
        JSONObject report = new JSONObject(true);
        report.put("evaluatedAt", Instant.now().toString());
        report.put("dataset", "after-sales-trajectory-v1");
        report.put("total", total);
        report.put("modelCallCount", total);
        report.put("modelContractPassed", modelContractPassed);
        report.put("modelContractPassRate", rate(modelContractPassed, total));
        report.put("governedRoutePassed", governedRoutePassed);
        report.put("governedRoutePassRate", rate(governedRoutePassed, total));
        report.put("latencyAverageMillis", sum / total);
        report.put("latencyP50Millis", percentile(sorted, 0.50));
        report.put("latencyP95Millis", percentile(sorted, 0.95));
        report.put("failures", failures);
        return report;
    }

    private JSONObject failure(JSONObject testCase, String phase, String message) {
        JSONObject result = new JSONObject(true);
        result.put("id", testCase.getString("id"));
        result.put("category", testCase.getString("category"));
        result.put("phase", phase);
        result.put("message", message);
        return result;
    }

    private double rate(int passed, int total) {
        return Math.round((passed * 10000.0) / total) / 100.0;
    }

    private long percentile(List<Long> sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(index);
    }

    private List<JSONObject> loadCases() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/evaluation/after-sales-trajectory-v1.jsonl");
        Assertions.assertNotNull(stream);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank()).map(JSON::parseObject).toList();
        }
    }

    private Map<String, Object> toGovernedInput(JSONObject testCase) {
        Map<String, Object> input = new LinkedHashMap<>();
        String userId = testCase.getString("userId");
        input.put(AfterSalesAgentState.USER_ID, userId);
        input.put(AfterSalesAgentState.SESSION_ID, "session-" + userId);
        input.put(AfterSalesAgentState.USER_MESSAGE, "我要退款");
        putText(input, AfterSalesAgentState.ORDER_ID, testCase.getString("orderId"));
        putText(input, AfterSalesAgentState.ORDER_OWNER_ID, testCase.getString("ownerId"));
        putText(input, AfterSalesAgentState.ORDER_STATUS, testCase.getString("status"));
        putText(input, AfterSalesAgentState.REFUND_REASON, testCase.getString("reason"));
        putText(input, AfterSalesAgentState.ERROR_TYPE, testCase.getString("errorType"));
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

    private void putNumber(JSONObject source, String key, Consumer<Integer> consumer) {
        if (source.containsKey(key)) {
            consumer.accept(source.getInteger(key));
        }
    }

    /**
     * 用于模型契约评估的订单网关：按 orderId 从轨迹数据集查找。
     */
    private static final class DeterministicOrderGateway implements IOrderGateway {
        private final Map<String, JSONObject> casesByOrderId;

        private DeterministicOrderGateway(List<JSONObject> cases) {
            this.casesByOrderId = new LinkedHashMap<>();
            for (JSONObject testCase : cases) {
                String orderId = testCase.getString("orderId");
                if (orderId != null && !orderId.isBlank()) {
                    casesByOrderId.put(orderId, testCase);
                }
            }
        }

        @Override
        public Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId) {
            JSONObject testCase = casesByOrderId.get(orderId);
            if (testCase == null) {
                return Optional.empty();
            }
            String ownerId = testCase.getString("ownerId");
            String status = testCase.getString("status");
            Integer days = testCase.containsKey("days") ? testCase.getInteger("days") : null;
            return Optional.of(new AfterSalesOrderSnapshot(orderId, ownerId, status, days));
        }
    }

    /**
     * 用于治理路由评估的工具端口：直接返回轨迹数据集中的订单快照，不调用真实模型。
     */
    private static final class TrajectoryToolPort implements IAfterSalesToolPort {
        private final JSONObject testCase;

        private TrajectoryToolPort(JSONObject testCase) {
            this.testCase = testCase;
        }

        @Override
        public AfterSalesToolResult executeReadOnly(cn.ethan.ai.domain.agent.model.AfterSalesToolRequest request,
                                                    AfterSalesToolContext context) {
            String orderId = JSON.parseObject(request.argumentsJson()).getString("orderId");
            String ownerId = testCase.getString("ownerId");
            String status = testCase.getString("status");
            Integer days = testCase.containsKey("days") ? testCase.getInteger("days") : null;
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("orderId", orderId);
            evidence.put("ownerId", ownerId);
            evidence.put("status", status);
            if (days != null) {
                evidence.put("daysSinceDelivery", days);
            }
            return AfterSalesToolResult.success("{}", new ToolEvidence("query_order", evidence));
        }
    }

    /**
     * 用于治理路由评估的仓库：支持退款幂等执行。
     */
    private static final class TrajectoryRepository implements IAfterSalesRepository {
        private final JSONObject testCase;

        private TrajectoryRepository(JSONObject testCase) {
            this.testCase = testCase;
        }

        @Override
        public Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId) {
            return Optional.of(new AfterSalesOrderSnapshot(
                    orderId,
                    testCase.getString("ownerId"),
                    testCase.getString("status"),
                    testCase.containsKey("days") ? testCase.getInteger("days") : null));
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
        public boolean tryAcquireResume(String caseId,
                                        String checkpointId,
                                        String resumeToken,
                                        long leaseSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseResume(String caseId, String resumeToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AfterSalesRefundResult executeRefund(String caseId, String orderId,
                                                    String userId, String idempotencyKey) {
            return new AfterSalesRefundResult(true, false, UUID.randomUUID().toString(), "REFUND_EXECUTED");
        }
    }
}
