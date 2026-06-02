package cn.ethan.ai.test.config;

import cn.ethan.ai.config.McpClientsHealthIndicator;
import cn.ethan.ai.domain.agent.adapter.port.IMcpClientLifecyclePort;
import cn.ethan.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.ethan.ai.domain.agent.model.valobj.McpClientLifecycleSnapshotVO;
import cn.ethan.ai.domain.agent.model.valobj.McpClientStateVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.McpClientLifecycleStatusEnumVO;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.List;
import java.util.Set;

public class McpClientsHealthIndicatorTest {

    @Test
    public void shouldReportUpWhenNoMcpIsConfigured() {
        Health health = indicator(McpClientLifecycleSnapshotVO.from(List.of())).health();

        Assert.assertEquals(Status.UP, health.getStatus());
        Assert.assertEquals("UP", health.getDetails().get("availability"));
    }

    @Test
    public void shouldReportDegradedDetailWithoutBreakingApplicationHealth() {
        McpClientStateVO failedClient = McpClientStateVO.builder()
                .mcpId("5001")
                .mcpName("context7")
                .status(McpClientLifecycleStatusEnumVO.FAILED)
                .lastError("IllegalStateException: connection failed")
                .build();

        Health health = indicator(McpClientLifecycleSnapshotVO.from(List.of(failedClient))).health();

        Assert.assertEquals(Status.UP, health.getStatus());
        Assert.assertEquals("DEGRADED", health.getDetails().get("availability"));
        Assert.assertEquals(1, health.getDetails().get("failedCount"));
    }

    private McpClientsHealthIndicator indicator(McpClientLifecycleSnapshotVO snapshot) {
        return new McpClientsHealthIndicator(new SnapshotPort(snapshot));
    }

    private record SnapshotPort(McpClientLifecycleSnapshotVO snapshot) implements IMcpClientLifecyclePort {

        @Override
        public void registerConfigurations(List<AiClientToolMcpVO> configurations) {
        }

        @Override
        public List<ToolCallback> resolveToolCallbacks(ToolRoutingDecisionVO routingDecision) {
            return List.of();
        }

        @Override
        public McpClientLifecycleSnapshotVO snapshot(Set<String> mcpIds) {
            return snapshot;
        }
    }
}
