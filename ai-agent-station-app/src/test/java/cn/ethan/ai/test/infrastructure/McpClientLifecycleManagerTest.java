package cn.ethan.ai.test.infrastructure;

import cn.ethan.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.infrastructure.adapter.port.McpClientLifecycleManager;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class McpClientLifecycleManagerTest {

    @Test
    public void shouldRegisterWithoutStartingClientsAndInitializeOnlyRoutedMcp() {
        StubMcpClientLifecycleManager manager = new StubMcpClientLifecycleManager();
        try {
            manager.registerConfigurations(List.of(
                    mcp("5001", "context7"),
                    mcp("5002", "exa")
            ));

            Assert.assertTrue(manager.attempts.isEmpty());

            List<ToolCallback> callbacks = manager.resolveToolCallbacks(decision("5002"));

            Assert.assertEquals(1, callbacks.size());
            Assert.assertEquals("tool_5002", callbacks.get(0).getToolDefinition().name());
            Assert.assertFalse(manager.attempts.containsKey("5001"));
            Assert.assertEquals(1, manager.attempts.get("5002").get());
        } finally {
            manager.close();
        }
    }

    @Test
    public void shouldRetryAfterInitializationFailure() {
        StubMcpClientLifecycleManager manager = new StubMcpClientLifecycleManager();
        try {
            manager.failOnce.add("5001");
            manager.registerConfigurations(List.of(mcp("5001", "context7")));

            Assert.assertTrue(manager.resolveToolCallbacks(decision("5001")).isEmpty());
            Assert.assertEquals(1, manager.resolveToolCallbacks(decision("5001")).size());
            Assert.assertEquals(2, manager.attempts.get("5001").get());
        } finally {
            manager.close();
        }
    }

    private AiClientToolMcpVO mcp(String mcpId, String name) {
        return AiClientToolMcpVO.builder()
                .mcpId(mcpId)
                .mcpName(name)
                .transportType("stdio")
                .build();
    }

    private ToolRoutingDecisionVO decision(String... mcpIds) {
        return ToolRoutingDecisionVO.builder()
                .enabled(true)
                .selectedMcpIds(Set.of(mcpIds))
                .build();
    }

    private static class StubMcpClientLifecycleManager extends McpClientLifecycleManager {

        private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

        private final Set<String> failOnce = ConcurrentHashMap.newKeySet();

        private StubMcpClientLifecycleManager() {
            super(false, 1, 1);
        }

        @Override
        protected List<ToolCallback> initializeToolCallbacks(AiClientToolMcpVO configuration) {
            attempts.computeIfAbsent(configuration.getMcpId(), ignored -> new AtomicInteger()).incrementAndGet();
            if (failOnce.remove(configuration.getMcpId())) {
                throw new IllegalStateException("first attempt failed");
            }
            return List.of(callback("tool_" + configuration.getMcpId()));
        }

        private ToolCallback callback(String name) {
            ToolDefinition definition = ToolDefinition.builder()
                    .name(name)
                    .description("test")
                    .inputSchema("{}")
                    .build();
            return new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return definition;
                }

                @Override
                public String call(String toolInput) {
                    return "ok";
                }
            };
        }
    }

}
