package cn.ethan.ai.domain.agent.service.armory;

import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.ArmoryAssemblyObjectKeyEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientApiVO;
import cn.ethan.ai.domain.agent.model.valobj.ArmoryAssemblyContextVO;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI API 配置节点
 */
@Slf4j
@Service
public class AiClientApiNode extends AbstractArmorySupport {

    @Resource
    private AiClientToolMcpNode aiClientToolMcpNode;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, ArmoryAssemblyContextVO assemblyContext) throws Exception {
        log.info("智能体装配节点，模型接口配置：{}", JSON.toJSONString(requestParameter));

        List<AiClientApiVO> aiClientApiList = assemblyContext.getValue(dataName());

        if (aiClientApiList == null || aiClientApiList.isEmpty()) {
            log.warn("没有需要初始化的模型接口配置");
            return router(requestParameter, assemblyContext);
        }

        Map<String, OpenAiChatOptions> apiOptionsMap = assemblyContext.getValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_API_OPTIONS_MAP_KEY.getCode());
        if (apiOptionsMap == null) {
            apiOptionsMap = new HashMap<>();
            assemblyContext.setValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_API_OPTIONS_MAP_KEY.getCode(), apiOptionsMap);
        }

        for (AiClientApiVO aiClientApiVO : aiClientApiList) {
            if (isUnresolvedApiKey(aiClientApiVO.getApiKey())) {
                log.warn("模型接口配置中的 API Key 仍为占位符或为空。若运行环境未提供 OPENAI_API_KEY，真实模型调用将失败。apiId：{}，baseUrl：{}",
                        aiClientApiVO.getApiId(), aiClientApiVO.getBaseUrl());
            }

            OpenAiChatOptions apiOptions = OpenAiChatOptions.builder()
                    .baseUrl(aiClientApiVO.getBaseUrl())
                    .apiKey(aiClientApiVO.getApiKey())
                    .build();
            apiOptionsMap.put(aiClientApiVO.getApiId(), apiOptions);

            // Spring AI 2.0 将连接配置收敛到不可变 Options，模型节点通过 mutate() 追加模型参数。
            registerBean(beanName(aiClientApiVO.getApiId()), OpenAiChatOptions.class, apiOptions);
        }

        return router(requestParameter, assemblyContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, ArmoryAssemblyContextVO, String> get(ArmoryCommandEntity armoryCommandEntity, ArmoryAssemblyContextVO assemblyContext) throws Exception {
        return aiClientToolMcpNode;
    }

    @Override
    protected String beanName(String beanId) {
        return AiAgentEnumVO.AI_CLIENT_API.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_API.getDataName();
    }

    private boolean isUnresolvedApiKey(String apiKey) {
        return !StringUtils.hasText(apiKey) || apiKey.contains("${");
    }

}
