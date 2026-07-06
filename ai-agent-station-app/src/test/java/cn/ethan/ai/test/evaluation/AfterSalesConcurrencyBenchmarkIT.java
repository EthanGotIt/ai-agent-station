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
import cn.ethan.ai.test.fixture.InMemoryCheckpointRepository;
import cn.ethan.ai.test.support.DotenvConditions;
import cn.ethan.ai.test.support.DotenvExtension;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@ExtendWith(DotenvExtension.class)
@EnabledIf(value = "cn.ethan.ai.test.support.DotenvConditions#isBenchmarkEnabled",
        disabledReason = "并发基准测试需通过 .env 开启")
public class AfterSalesConcurrencyBenchmarkIT {

    @Test
    void shouldMeasureJava17BoundedExecutorBaseline() throws Exception {
        int tasks = 200;
        int clientConcurrency = 32;
        int ioConcurrency = 16;
        ExecutorService ioExecutor = new ThreadPoolExecutor(
                ioConcurrency, ioConcurrency, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(ioConcurrency * 32),
                new ThreadPoolExecutor.CallerRunsPolicy());
        ExecutorService clients = Executors.newFixedThreadPool(clientConcurrency);
        AtomicInteger errors = new AtomicInteger();
        List<Long> latencies = new ArrayList<>();
        try {
            SpringStateMachineAdapter runtime = new SpringStateMachineAdapter(
                    new DelayedToolPort(),
                    new NoOpRepository(),
                    new RefundPlanningAgent(null),
                    new RefundInformationGatheringPolicy(),
                    null,
                    new InMemoryCheckpointRepository());
            for (int index = 0; index < 20; index++) {
                execute(runtime, -index - 1, errors);
            }
            Assertions.assertEquals(0, errors.get(), "benchmark warmup failed");
            errors.set(0);
            Instant benchmarkStart = Instant.now();
            List<Future<Long>> futures = new ArrayList<>();
            for (int index = 0; index < tasks; index++) {
                int sequence = index;
                futures.add(clients.submit(() -> execute(runtime, sequence, errors)));
            }
            for (Future<Long> future : futures) {
                latencies.add(future.get());
            }
            long totalMillis = Duration.between(benchmarkStart, Instant.now()).toMillis();
            List<Long> sorted = latencies.stream().sorted(Comparator.naturalOrder()).toList();
            JSONObject report = new JSONObject(true);
            report.put("javaVersion", System.getProperty("java.version"));
            report.put("tasks", tasks);
            report.put("clientConcurrency", clientConcurrency);
            report.put("ioConcurrency", ioConcurrency);
            report.put("errors", errors.get());
            report.put("totalMillis", totalMillis);
            report.put("throughputPerSecond", Math.round(tasks * 1000.0 / totalMillis * 100.0) / 100.0);
            report.put("latencyP50Millis", percentile(sorted, 0.50));
            report.put("latencyP95Millis", percentile(sorted, 0.95));
            report.put("latencyP99Millis", percentile(sorted, 0.99));
            report.put("latencyMaxMillis", sorted.get(sorted.size() - 1));
            Path output = Path.of("target", "evaluation", "after-sales-java17-benchmark.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, JSON.toJSONString(report, true), StandardCharsets.UTF_8);

            Assertions.assertEquals(0, errors.get(), report.toJSONString());
            Assertions.assertTrue(percentile(sorted, 0.95) < 1000, report.toJSONString());
        } finally {
            clients.shutdownNow();
            ioExecutor.shutdownNow();
        }
    }

    private long execute(SpringStateMachineAdapter runtime, int sequence, AtomicInteger errors) {
        Instant startedAt = Instant.now();
        String orderId = "BENCH-" + sequence;
        try {
            AfterSalesAgentState state = runtime.execute(Map.of(
                    AfterSalesAgentState.CASE_ID, UUID.randomUUID().toString(),
                    AfterSalesAgentState.USER_ID, "benchmark-user",
                    AfterSalesAgentState.SESSION_ID, "session-benchmark-user",
                    AfterSalesAgentState.USER_MESSAGE, "退款订单 " + orderId,
                    AfterSalesAgentState.ORDER_ID, orderId,
                    AfterSalesAgentState.REFUND_REASON, "DAMAGED"
            ), UUID.randomUUID().toString());
            if (state.stage() != AfterSalesStage.PENDING_APPROVAL) {
                errors.incrementAndGet();
            }
        } catch (RuntimeException error) {
            errors.incrementAndGet();
        }
        return Duration.between(startedAt, Instant.now()).toMillis();
    }

    private long percentile(List<Long> sorted, double percentile) {
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1));
    }

    private static final class DelayedToolPort implements IAfterSalesToolPort {
        @Override
        public AfterSalesToolRequest proposeOrderQuery(String userMessage, String userId, String sessionId,
                                                       String orderIdHint, String refundReason,
                                                       String correction) {
            delay();
            return new AfterSalesToolRequest(UUID.randomUUID().toString(),
                    AfterSalesToolContractValidator.QUERY_ORDER_TOOL,
                    JSON.toJSONString(Map.of("orderId", orderIdHint)));
        }

        @Override
        public AfterSalesToolResult executeOrderQuery(AfterSalesToolRequest request,
                                                      String userId,
                                                      String userMessage) {
            delay();
            String orderId = JSON.parseObject(request.argumentsJson()).getString("orderId");
            return AfterSalesToolResult.success("{}",
                    new AfterSalesOrderSnapshot(orderId, userId, "PAID", null));
        }

        private void delay() {
            try {
                Thread.sleep(15);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
        }
    }

    private static final class NoOpRepository implements IAfterSalesRepository {
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
