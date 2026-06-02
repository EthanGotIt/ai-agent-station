package cn.ethan.ai.domain.agent.model.valobj;

import cn.ethan.ai.domain.agent.model.valobj.enums.McpClientLifecycleStatusEnumVO;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * MCP 客户端生命周期聚合快照。
 */
@Value
@Builder
public class McpClientLifecycleSnapshotVO {

    List<McpClientStateVO> clients;

    int configuredCount;

    int registeredCount;

    int initializingCount;

    int readyCount;

    int failedCount;

    public static McpClientLifecycleSnapshotVO from(List<McpClientStateVO> clients) {
        List<McpClientStateVO> safeClients = clients == null ? List.of() : List.copyOf(clients);
        return McpClientLifecycleSnapshotVO.builder()
                .clients(safeClients)
                .configuredCount(safeClients.size())
                .registeredCount(count(safeClients, McpClientLifecycleStatusEnumVO.REGISTERED))
                .initializingCount(count(safeClients, McpClientLifecycleStatusEnumVO.INITIALIZING))
                .readyCount(count(safeClients, McpClientLifecycleStatusEnumVO.READY))
                .failedCount(count(safeClients, McpClientLifecycleStatusEnumVO.FAILED))
                .build();
    }

    private static int count(List<McpClientStateVO> clients, McpClientLifecycleStatusEnumVO status) {
        return (int) clients.stream()
                .filter(client -> status == client.getStatus())
                .count();
    }
}
