package cn.ethan.ai.domain.agent.adapter.port;

import cn.ethan.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.ethan.ai.domain.agent.model.valobj.McpClientLifecycleSnapshotVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;

/**
 * MCP 客户端生命周期端口。
 * Domain 只登记配置和按路由获取工具，不感知具体传输与 SDK 初始化细节。
 */
public interface IMcpClientLifecyclePort {

    void registerConfigurations(List<AiClientToolMcpVO> configurations);

    List<ToolCallback> resolveToolCallbacks(ToolRoutingDecisionVO routingDecision);

    McpClientLifecycleSnapshotVO snapshot();

    McpClientLifecycleSnapshotVO snapshot(Set<String> mcpIds);

}
