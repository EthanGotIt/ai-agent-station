package cn.ethan.ai.domain.agent.service.armory;

import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.ArmoryAssemblyObjectKeyEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientSystemPromptVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientVO;
import cn.ethan.ai.domain.agent.model.valobj.ArmoryAssemblyContextVO;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体对话客户端装配节点
 */
@Slf4j
@Service
public class AiClientNode extends AbstractArmorySupport {

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, ArmoryAssemblyContextVO assemblyContext) throws Exception {
        log.info("智能体装配节点，对话客户端配置：{}", JSON.toJSONString(requestParameter));

        List<AiClientVO> aiClientList = assemblyContext.getValue(dataName());

        if (null == aiClientList || aiClientList.isEmpty()) {
            return router(requestParameter, assemblyContext);
        }

        Map<String, AiClientSystemPromptVO> systemPromptMap = assemblyContext.getValue(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName());

        Map<String, org.springframework.ai.openai.OpenAiChatModel> modelObjectMap =
                assemblyContext.getValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_MODEL_OBJECT_MAP_KEY.getCode());
        Map<String, ChatClient> chatClientObjectMap =
                assemblyContext.getValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_CHAT_CLIENT_OBJECT_MAP_KEY.getCode());

        if (chatClientObjectMap == null) {
            chatClientObjectMap = new HashMap<>();
            assemblyContext.setValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_CHAT_CLIENT_OBJECT_MAP_KEY.getCode(), chatClientObjectMap);
        }

        for (AiClientVO aiClientVO : aiClientList) {
            // 1. 预设话术
            StringBuilder defaultSystem = new StringBuilder("Ai 智能体 \r\n");
            List<String> promptIdList = aiClientVO.getPromptIdList();
            for (String promptId : promptIdList) {
                AiClientSystemPromptVO aiClientSystemPromptVO = systemPromptMap.get(promptId);
                defaultSystem.append(aiClientSystemPromptVO.getPromptContent());
            }

            // 2. 对话模型
            if (modelObjectMap == null) {
                throw new RuntimeException("对话模型对象缓存为空，无法装配对话客户端");
            }
            OpenAiChatModel chatModel = modelObjectMap.get(aiClientVO.getModelId());

            // 3. 构建对话客户端。Session 记忆、RAG 和工具注入由 Harness 执行期显式管理。
            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultSystem(defaultSystem.toString())
                    .build();

            chatClientObjectMap.put(aiClientVO.getClientId(), chatClient);

            // 向 Spring 容器注册：执行阶段通过名称获取对话客户端。
            registerBean(beanName(aiClientVO.getClientId()), ChatClient.class, chatClient);
        }

        return String.format("已完成对话客户端自动装配，数量=%d，客户端ID=%s", aiClientList.size(), requestParameter.getCommandIdList());
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, ArmoryAssemblyContextVO, String> get(ArmoryCommandEntity requestParameter, ArmoryAssemblyContextVO assemblyContext) throws Exception {
        return defaultStrategyHandler;
    }

    @Override
    protected String beanName(String id) {
        return AiAgentEnumVO.AI_CLIENT.getBeanName(id);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT.getDataName();
    }

}
