package cn.ethan.ai.config;

import cn.ethan.ai.domain.agent.adapter.port.IMcpClientLifecyclePort;
import cn.ethan.ai.domain.agent.model.valobj.McpClientLifecycleSnapshotVO;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * MCP 客户端健康指示器。
 * MCP 是可选能力，初始化失败只标记降级，不阻断应用 readiness。
 */
@Component("mcpClients")
public class McpClientsHealthIndicator implements HealthIndicator {

    private final IMcpClientLifecyclePort mcpClientLifecyclePort;

    public McpClientsHealthIndicator(IMcpClientLifecyclePort mcpClientLifecyclePort) {
        this.mcpClientLifecyclePort = mcpClientLifecyclePort;
    }

    @Override
    public Health health() {
        McpClientLifecycleSnapshotVO snapshot = mcpClientLifecyclePort.snapshot();
        String availability = snapshot.getFailedCount() > 0 ? "DEGRADED" : "UP";
        return Health.up()
                .withDetail("availability", availability)
                .withDetail("configuredCount", snapshot.getConfiguredCount())
                .withDetail("registeredCount", snapshot.getRegisteredCount())
                .withDetail("initializingCount", snapshot.getInitializingCount())
                .withDetail("readyCount", snapshot.getReadyCount())
                .withDetail("failedCount", snapshot.getFailedCount())
                .withDetail("clients", snapshot.getClients())
                .build();
    }
}
