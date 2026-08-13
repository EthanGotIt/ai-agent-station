package cn.ethan.infrastructure.agentscope.executor;

import cn.ethan.core.agent.enums.AgentMemoryCategoryEnum;
import cn.ethan.core.agent.enums.AgentMemoryOriginEnum;
import cn.ethan.core.agent.model.AgentMemoryEntryModel;
import cn.ethan.core.agent.model.OutputEventModel;
import cn.ethan.core.agent.service.AgentMemoryService;
import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.model.RefundCommandModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import cn.ethan.core.order.model.OrderLookupResultModel;
import cn.ethan.core.order.port.LogisticsGateway;
import cn.ethan.core.order.port.OrderGateway;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentScope ReAct 装配测试：验证 Skill 只提供指导，不收缩常驻 Tool 或运行时权限边界。
 *
 * @author ethan
 * @date 2026-08-11
 */
class AgentScopeReActExecutorTest {

    @Test
    void registersBusinessSkillAndKeepsAllProductionToolsAvailable() throws Exception {
        try (ClasspathSkillRepository repository = new ClasspathSkillRepository("agentscope/skills", "test")) {
            AgentScopeReActExecutor executor = new AgentScopeReActExecutor(
                    "test-key", "", "qwen3.7-plus", Duration.ofSeconds(10), 4, 512, 1,
                    true, 256, orderGateway(), logisticsGateway(), afterSalesCaseGateway(),
                    refundCommandGateway(), new AgentMemoryService(false, false, 0.75, null, Clock.systemUTC()),
                    repository, false, null, Clock.systemUTC()
            );
            ReActAgent agent = executor.buildAgent();
            try {
                assertTrue(agent.getSysPrompt().contains("agent-station-business-orchestration"));
                assertTrue(agent.getSysPrompt().contains("load_skill_through_path"));
                assertTrue(agent.getSysPrompt().contains("服务端会在每个业务分析或会话偏好回合开始前"));
                assertTrue(agent.getSysPrompt().contains("服务端存储且已校验的展示配置"));
                assertFalse(agent.getSysPrompt().contains("get_order_snapshot"));

                DynamicSkillMiddleware middleware = agent.getMiddlewares().stream()
                        .filter(DynamicSkillMiddleware.class::isInstance)
                        .map(DynamicSkillMiddleware.class::cast)
                        .findFirst()
                        .orElseThrow();
                String effectivePrompt = middleware.onSystemPrompt(
                        agent,
                        RuntimeContext.builder().userId("user-1").sessionId("session-1").build(),
                        agent.getSysPrompt()
                ).block();

                assertTrue(effectivePrompt.contains("agent-station-business-orchestration"));
                assertTrue(effectivePrompt.contains("安全工具选择"));

                Set<String> toolNames = agent.getToolkit().getToolNames();
                assertTrue(toolNames.contains("load_skill_through_path"));
                assertTrue(toolNames.containsAll(Set.of(
                        "list_recent_orders", "get_order_snapshot", "get_logistics_trace",
                        "get_after_sales_status", "get_after_sales_policy", "save_session_preference"
                )));
                assertFalse(toolNames.contains("reset_equipped_tools"));
            } finally {
                agent.close();
                executor.close();
            }
        }
    }

    @Test
    void loadsBusinessSkillBeforeModelAndExposesOnlyToolLifecycle() throws Exception {
        try (ClasspathSkillRepository repository = new ClasspathSkillRepository("agentscope/skills", "test")) {
            AgentScopeReActExecutor executor = new AgentScopeReActExecutor(
                    "test-key", "", "qwen3.7-plus", Duration.ofSeconds(10), 4, 512, 1,
                    true, 256, orderGateway(), logisticsGateway(), afterSalesCaseGateway(),
                    refundCommandGateway(), new AgentMemoryService(false, false, 0.75, null, Clock.systemUTC()),
                    repository, false, null, Clock.systemUTC()
            );
            ReActAgent agent = executor.buildAgent();
            try {
                List<OutputEventModel> events = new ArrayList<>();
                String content = executor.loadBusinessSkill(
                        agent,
                        RuntimeContext.builder().userId("user-1").sessionId("session-1").build(),
                        "request-1",
                        new CancellationToken(),
                        events::add
                );

                assertTrue(content.contains("`get_order_snapshot`"));
                assertTrue(content.contains("`save_session_preference`"));
                assertEquals(List.of(
                        "load_skill_through_path",
                        "load_skill_through_path:SUCCESS"
                ), events.stream().map(OutputEventModel::value).toList());
                assertFalse(agent.getToolkit().getActiveGroups().contains("skill-build-in-tools"));
                assertFalse(events.stream().anyMatch(event -> event.value().contains("Tool 矩阵")));
            } finally {
                agent.close();
                executor.close();
            }
        }
    }

    @Test
    void rendersLanguagePreferenceAsTrustedOutputConstraint() {
        Instant now = Instant.parse("2026-08-11T00:00:00Z");
        AgentMemoryEntryModel preference = new AgentMemoryEntryModel(
                "entry-1", null, "user-1", "session-1", AgentMemoryCategoryEnum.PREFERENCE,
                "response.language", "en-US", AgentMemoryOriginEnum.MANUAL, 1.0, 0,
                false, null, now, now
        );

        String prompt = AgentScopeReActExecutor.renderUserMessage(
                List.of(), List.of(preference), "请复盘订单 ORDER-PAID-001"
        );

        assertTrue(prompt.contains("最终回答必须仅使用英文，不得出现中文"));
        assertFalse(prompt.contains("- PREFERENCE response.language"));
    }

    @Test
    void recognizesOnlyFullyRejectedConfirmationResults() {
        ToolUseBlock first = ToolUseBlock.builder()
                .id("tool-call-1").name("save_session_preference").input(Map.of()).build();
        ToolUseBlock second = ToolUseBlock.builder()
                .id("tool-call-2").name("save_session_preference").input(Map.of()).build();

        assertTrue(AgentScopeReActExecutor.allRejected(List.of(
                new ConfirmResult(false, first),
                new ConfirmResult(false, second)
        )));
        assertFalse(AgentScopeReActExecutor.allRejected(List.of(
                new ConfirmResult(false, first),
                new ConfirmResult(true, second)
        )));
        assertFalse(AgentScopeReActExecutor.allRejected(List.of()));
    }

    @Test
    void closeUnblocksPendingInterventionAndUsesInjectedClock() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-13T00:00:00Z"));
        try (ClasspathSkillRepository repository = new ClasspathSkillRepository("agentscope/skills", "test")) {
            AgentScopeReActExecutor executor = new AgentScopeReActExecutor(
                    "test-key", "", "qwen3.7-plus", Duration.ofSeconds(10), 4, 512, 1,
                    true, 256, orderGateway(), logisticsGateway(), afterSalesCaseGateway(),
                    refundCommandGateway(), new AgentMemoryService(false, false, 0.75, null, clock),
                    repository, false, null, clock
            );
            Object pending = pendingIntervention(clock);
            CompletableFuture<?> decision = pendingDecision(pending);
            pendingMap(executor).put("reply-1", pending);
            clock.advance(Duration.ofSeconds(4));

            assertEquals(Duration.ofSeconds(4), pendingWaitDuration(pending));
            executor.close();
            executor.close();

            assertInstanceOf(CancellationException.class,
                    assertThrows(CancellationException.class, decision::join));
            assertTrue(pendingMap(executor).isEmpty());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pendingMap(AgentScopeReActExecutor executor) throws Exception {
        Field field = AgentScopeReActExecutor.class.getDeclaredField("pendingInterventions");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(executor);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<?> pendingDecision(Object pending) throws Exception {
        Field field = pending.getClass().getDeclaredField("decision");
        field.setAccessible(true);
        return (CompletableFuture<?>) field.get(pending);
    }

    private Object pendingIntervention(Clock clock) throws Exception {
        Class<?> type = Class.forName(AgentScopeReActExecutor.class.getName() + "$PendingIntervention");
        Constructor<?> constructor = type.getDeclaredConstructor(
                String.class, String.class, String.class, RuntimeContext.class, String.class, List.class, Clock.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance("request-1", "user-1", "session-1", null, "reply-1", List.of(), clock);
    }

    private Duration pendingWaitDuration(Object pending) throws Exception {
        Method method = pending.getClass().getDeclaredMethod("waitDuration");
        method.setAccessible(true);
        return (Duration) method.invoke(pending);
    }

    private OrderGateway orderGateway() {
        return (orderId, userId) -> OrderLookupResultModel.notFound();
    }

    private LogisticsGateway logisticsGateway() {
        return (orderId, userId) -> List.of();
    }

    private AfterSalesCaseGateway afterSalesCaseGateway() {
        return new AfterSalesCaseGateway() {
            @Override
            public Optional<AfterSalesCaseModel> findByOrder(String orderId, String userId) {
                return Optional.empty();
            }

            @Override
            public Optional<AfterSalesCaseModel> findByWorkflowRunId(String workflowRunId) {
                return Optional.empty();
            }

            @Override
            public AfterSalesCaseModel create(AfterSalesCaseModel caseModel) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean update(AfterSalesCaseModel expected, AfterSalesCaseModel updated) {
                return false;
            }
        };
    }

    private RefundCommandGateway refundCommandGateway() {
        return new RefundCommandGateway() {
            @Override
            public RefundCommandResultModel create(RefundCommandModel command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<RefundCommandResultModel> findByOrder(String orderId, String userId) {
                return Optional.empty();
            }
        };
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
