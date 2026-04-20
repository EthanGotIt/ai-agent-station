package cn.ethan.ai.domain.agent.service.armory;

import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.ArmoryAssemblyObjectKeyEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientModelVO;
import cn.ethan.ai.domain.agent.model.valobj.ArmoryAssemblyContextVO;
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
    protected String doApply(ArmoryCommandEntity requestParameter, ArmoryAssemblyContextVO assemblyContext) throws Exception {
        log.info("智能体装配节点，对话模型配置：{}", JSON.toJSONString(requestParameter));

        List<AiClientModelVO> aiClientModelList = assemblyContext.getValue(dataName());

        if (aiClientModelList == null || aiClientModelList.isEmpty()) {
            log.warn("没有需要初始化的对话模型配置");
            return router(requestParameter, assemblyContext);
        }

        Map<String, OpenAiApi> apiObjectMap = assemblyContext.getValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_API_OBJECT_MAP_KEY.getCode());
        Map<String, McpSyncClient> mcpObjectMap = assemblyContext.getValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_TOOL_MCP_OBJECT_MAP_KEY.getCode());
        Map<String, OpenAiChatModel> modelObjectMap = assemblyContext.getValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_MODEL_OBJECT_MAP_KEY.getCode());
        if (modelObjectMap == null) {
            modelObjectMap = new HashMap<>();
            assemblyContext.setValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_MODEL_OBJECT_MAP_KEY.getCode(), modelObjectMap);
        }

        for (AiClientModelVO modelVO : aiClientModelList) {

            if (apiObjectMap == null || mcpObjectMap == null) {
                throw new RuntimeException("模型接口对象或工具客户端对象为空，无法装配对话模型");
            }

            // 获取当前模型关联的 API 对象（来自装配上下文）
            OpenAiApi openAiApi = apiObjectMap.get(modelVO.getApiId());
            if (null == openAiApi) {
                throw new RuntimeException("模型关联的接口对象不存在，modelId=" + modelVO.getModelId() + "，apiId=" + modelVO.getApiId());
            }

            // 获取当前模型关联的 MCP 工具客户端对象。
            List<McpSyncClient> mcpSyncClients = new ArrayList<>();
            for (String toolMcpId : modelVO.getToolMcpIds()) {
                McpSyncClient mcpSyncClient = mcpObjectMap.get(toolMcpId);
                if (mcpSyncClient != null) {
                    mcpSyncClients.add(mcpSyncClient);
                }
            }

            // 实例化对话模型，其他模型可通过 OpenAI 兼容接口接入。
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

            // 放入装配上下文，供后续对话客户端装配。
            modelObjectMap.put(modelVO.getModelId(), chatModel);

            // 向 Spring 容器注册：执行阶段可通过名称获取对话模型。
            registerBean(beanName(modelVO.getModelId()), OpenAiChatModel.class, chatModel);
        }

        return router(requestParameter, assemblyContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, ArmoryAssemblyContextVO, String> get(ArmoryCommandEntity requestParameter, ArmoryAssemblyContextVO assemblyContext) throws Exception {
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
