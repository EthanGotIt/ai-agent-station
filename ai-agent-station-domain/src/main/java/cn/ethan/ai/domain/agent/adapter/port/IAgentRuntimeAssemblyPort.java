package cn.ethan.ai.domain.agent.adapter.port;

import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Graph Runtime 装配端口，隔离 Spring 容器中的动态模型和 MCP ToolCallback。
 */
public interface IAgentRuntimeAssemblyPort {

    ChatClient resolveChatClient(String clientId);

    ChatModel resolveChatModel(String clientId);

    List<ToolCallback> resolveMcpToolCallbacks(ToolRoutingDecisionVO toolRoutingDecision);

}
