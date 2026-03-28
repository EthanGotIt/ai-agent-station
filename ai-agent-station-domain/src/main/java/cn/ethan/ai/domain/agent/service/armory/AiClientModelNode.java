package cn.ethan.ai.domain.agent.service.armory;

import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.DynamicContextObjectKeyEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientModelVO;
import cn.ethan.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话模型节点配置
 */
@Slf4j
@Service
public class AiClientModelNode extends AbstractArmorySupport {

    @Resource
    private AiClientAdvisorNode aiClientAdvisorNode;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，Mode 对话模型{}", JSON.toJSONString(requestParameter));

        List<AiClientModelVO> aiClientModelList = dynamicContext.getValue(dataName());

        if (aiClientModelList == null || aiClientModelList.isEmpty()) {
            log.warn("没有需要被初始化的 ai client model");
            return router(requestParameter, dynamicContext);
        }

        Map<String, OpenAiApi> apiObjectMap = dynamicContext.getValue(DynamicContextObjectKeyEnumVO.AI_CLIENT_API_OBJECT_MAP_KEY.getCode());
        Map<String, McpSyncClient> mcpObjectMap = dynamicContext.getValue(DynamicContextObjectKeyEnumVO.AI_CLIENT_TOOL_MCP_OBJECT_MAP_KEY.getCode());
        Map<String, OpenAiChatModel> modelObjectMap = dynamicContext.getValue(DynamicContextObjectKeyEnumVO.AI_CLIENT_MODEL_OBJECT_MAP_KEY.getCode());
        if (modelObjectMap == null) {
            modelObjectMap = new HashMap<>();
            dynamicContext.setValue(DynamicContextObjectKeyEnumVO.AI_CLIENT_MODEL_OBJECT_MAP_KEY.getCode(), modelObjectMap);
        }

        for (AiClientModelVO modelVO : aiClientModelList) {

            if (apiObjectMap == null || mcpObjectMap == null) {
                throw new RuntimeException("ai_client_api_object_map 或 ai_client_tool_mcp_object_map 为空，无法装配模型");
            }

            // 获取当前模型关联的 API 对象（来自请求级 DynamicContext）
            OpenAiApi openAiApi = apiObjectMap.get(modelVO.getApiId());
            if (null == openAiApi) {
                throw new RuntimeException("mode 2 api is null");
            }

            // 获取当前模型关联的 Tool MCP 对象（来自请求级 DynamicContext）
            List<McpSyncClient> mcpSyncClients = new ArrayList<>();
            for (String toolMcpId : modelVO.getToolMcpIds()) {
                McpSyncClient mcpSyncClient = mcpObjectMap.get(toolMcpId);
                if (mcpSyncClient != null) {
                    mcpSyncClients.add(mcpSyncClient);
                }
            }

            // 实例化对话模型（如果有其他模型对接，可以使用 one-api 服务，转换为 openai 模型格式）
            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(
                            OpenAiChatOptions.builder()
                                    .model(modelVO.getModelName())
                                    .toolCallbacks(SyncMcpToolCallbackProvider.builder()
                                            .mcpClients(mcpSyncClients)
                                            .build()
                                            .getToolCallbacks())
                                    .build())
                    .build();

            // 放入请求级 DynamicContext，供后续 ChatClient 装配
            modelObjectMap.put(modelVO.getModelId(), chatModel);

            // 向 Spring 容器注册：执行阶段可通过 beanName 获取 OpenAiChatModel
            registerBean(beanName(modelVO.getModelId()), OpenAiChatModel.class, chatModel);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientAdvisorNode;
    }

    @Override
    protected String beanName(String beanId) {
        return AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_MODEL.getDataName();
    }

}
