package cn.ethan.ai.test.evaluation;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundResult;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.model.AfterSalesToolContext;
import cn.ethan.ai.domain.agent.model.ToolEvidence;
import cn.ethan.ai.domain.agent.model.plan.PlanningContext;
import cn.ethan.ai.domain.agent.policy.RefundInformationGatheringPolicy;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
import cn.ethan.ai.infrastructure.adapter.ai.SpringAiAfterSalesToolAdapter;
import cn.ethan.ai.infrastructure.adapter.statemachine.SpringStateMachineAdapter;
import cn.ethan.ai.infrastructure.json.AfterSalesJsonCodec;
import cn.ethan.ai.test.fixture.InMemoryCheckpointRepository;
import cn.ethan.ai.test.fixture.UnsupportedAfterSalesRepository;
import cn.ethan.ai.test.support.DotenvConditions;
import cn.ethan.ai.test.support.DotenvExtension;
import tools.jackson.core.type.TypeReference;
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

    private static final AfterSalesJsonCodec JSON = AfterSalesJsonCodec.defaultCodec();

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
        List<Map<String, Object>> cases = loadCases();
        Assertions.assertEquals(30, cases.size());

        // 实时模型评测只验证模型 Plan 契约，Session Memory 由独立集成路径覆盖。
        ChatClient planningClient = ChatClient.builder(chatModel)
                .defaultOptions(OpenAiChatOptions.builder().model(planningModel).temperature(0.0))
                .build();
        RefundPlanningAgent planningAgent = new RefundPlanningAgent(planningClient);
        List<Long> latencies = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        int modelContractPassed = 0;
        int governedRoutePassed = 0;

        for (Map<String, Object> testCase : cases) {
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
                    new TrajectoryRepository(),
                    new RefundPlanningAgent(null),
                    new RefundInformationGatheringPolicy(),
                    null,
                    new InMemoryCheckpointRepository())
                    .execute(toGovernedInput(testCase), "eval-" + text(testCase, "id")).state();
            String expectedStage = text(testCase, "expectedStage");
            if (expectedStage.equals(state.stage().name())) {
                governedRoutePassed++;
            } else {
                failures.add(failure(testCase, "GOVERNED_ROUTE",
                        "expected=" + expectedStage + ",actual=" + state.stage().name()));
            }
        }

        Map<String, Object> report = report(cases.size(), modelContractPassed, governedRoutePassed, latencies, failures);
        Path output = Path.of("target", "evaluation", "after-sales-live-evaluation.json");
        Files.createDirectories(output.getParent());
        String reportJson = JSON.writePretty(report, "序列化实时评估报告");
        Files.writeString(output, reportJson, StandardCharsets.UTF_8);

        Assertions.assertEquals(30, modelContractPassed, reportJson);
        Assertions.assertEquals(30, governedRoutePassed, reportJson);
    }

    private String evaluateModelContract(RefundPlanningAgent planningAgent, Map<String, Object> testCase) {
        String caseId = text(testCase, "id");
        String expectedOrderId = text(testCase, "orderId");
        boolean expectNoToolCall = "rule-01".equals(caseId);
        if (expectedOrderId == null && !expectNoToolCall) {
            expectedOrderId = "probe-" + caseId;
        }
        final String expectedOrderIdForPlan = expectedOrderId;
        String message = expectNoToolCall ? "我要退款，商品有问题" : "我要退款，订单号 " + expectedOrderId;
        try {
            String userId = Optional.ofNullable(text(testCase, "userId")).orElse("eval-user");
            PlanningContext context = new PlanningContext(
                    "live-" + caseId, userId, "session-" + userId, message,
                    expectNoToolCall ? null : expectedOrderIdForPlan, null,
                    Optional.ofNullable(text(testCase, "reason")).orElse("DAMAGED"),
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

    private Map<String, Object> report(int total,
                              int modelContractPassed,
                              int governedRoutePassed,
                              List<Long> latencies,
                              List<Map<String, Object>> failures) {
        List<Long> sorted = latencies.stream().sorted(Comparator.naturalOrder()).toList();
        long sum = latencies.stream().mapToLong(Long::longValue).sum();
        Map<String, Object> report = new LinkedHashMap<>();
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

    private Map<String, Object> failure(Map<String, Object> testCase, String phase, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", text(testCase, "id"));
        result.put("category", text(testCase, "category"));
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

    private List<Map<String, Object>> loadCases() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/evaluation/after-sales-trajectory-v1.jsonl");
        Assertions.assertNotNull(stream);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank())
                    .map(line -> JSON.read(line, new TypeReference<Map<String, Object>>() {
                    }, "解析实时评估轨迹"))
                    .toList();
        }
    }

    private Map<String, Object> toGovernedInput(Map<String, Object> testCase) {
        Map<String, Object> input = new LinkedHashMap<>();
        String userId = text(testCase, "userId");
        input.put(AfterSalesAgentState.USER_ID, userId);
        input.put(AfterSalesAgentState.SESSION_ID, "session-" + userId);
        input.put(AfterSalesAgentState.USER_MESSAGE, "我要退款");
        putText(input, AfterSalesAgentState.ORDER_ID, text(testCase, "orderId"));
        putText(input, AfterSalesAgentState.ORDER_OWNER_ID, text(testCase, "ownerId"));
        putText(input, AfterSalesAgentState.ORDER_STATUS, text(testCase, "status"));
        putText(input, AfterSalesAgentState.REFUND_REASON, text(testCase, "reason"));
        putText(input, AfterSalesAgentState.ERROR_TYPE, text(testCase, "errorType"));
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

    /**
     * 用于治理路由评估的工具端口：直接返回轨迹数据集中的订单快照，不调用真实模型。
     */
    private static final class TrajectoryToolPort implements IAfterSalesToolPort {
        private final Map<String, Object> testCase;

        private TrajectoryToolPort(Map<String, Object> testCase) {
            this.testCase = testCase;
        }

        @Override
        public AfterSalesToolResult executeReadOnly(cn.ethan.ai.domain.agent.model.AfterSalesToolRequest request,
                                                    AfterSalesToolContext context) {
            Map<String, Object> arguments = JSON.read(request.argumentsJson(), new TypeReference<>() {
            }, "解析实时评估工具参数");
            String orderId = text(arguments, "orderId");
            String ownerId = text(testCase, "ownerId");
            String status = text(testCase, "status");
            Integer days = testCase.containsKey("days") ? ((Number) testCase.get("days")).intValue() : null;
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
    private static final class TrajectoryRepository extends UnsupportedAfterSalesRepository {
        @Override
        public AfterSalesRefundResult executeRefund(String caseId, String orderId,
                                                    String userId, String idempotencyKey) {
            return new AfterSalesRefundResult(true, false, UUID.randomUUID().toString(), "REFUND_EXECUTED");
        }
    }
}
