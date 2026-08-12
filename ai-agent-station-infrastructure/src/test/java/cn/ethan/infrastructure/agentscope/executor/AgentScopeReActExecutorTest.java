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
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                    repository, false, null
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
                    repository, false, null
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
                assertTrue(agent.getToolkit().getActiveGroups().contains("skill-build-in-tools"));
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
}
