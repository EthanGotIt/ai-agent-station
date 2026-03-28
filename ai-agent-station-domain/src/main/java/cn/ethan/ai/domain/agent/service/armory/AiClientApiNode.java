package cn.ethan.ai.domain.agent.service.armory;

import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.DynamicContextObjectKeyEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientApiVO;
import cn.ethan.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI API配置节点
 */
@Slf4j
@Service
public class AiClientApiNode extends AbstractArmorySupport {

    @Resource
    private AiClientToolMcpNode aiClientToolMcpNode;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，API 接口请求{}", JSON.toJSONString(requestParameter));

        List<AiClientApiVO> aiClientApiList = dynamicContext.getValue(dataName());

        if (aiClientApiList == null || aiClientApiList.isEmpty()) {
            log.warn("没有需要被初始化的 ai client api");
            return router(requestParameter, dynamicContext);
        }

        Map<String, OpenAiApi> apiObjectMap = dynamicContext.getValue(DynamicContextObjectKeyEnumVO.AI_CLIENT_API_OBJECT_MAP_KEY.getCode());
        if (apiObjectMap == null) {
            apiObjectMap = new HashMap<>();
            dynamicContext.setValue(DynamicContextObjectKeyEnumVO.AI_CLIENT_API_OBJECT_MAP_KEY.getCode(), apiObjectMap);
        }

        for (AiClientApiVO aiClientApiVO : aiClientApiList) {
            // 仅在请求级 DynamicContext 里组装对象，避免动态 registerBean 带来的并发问题
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(aiClientApiVO.getBaseUrl())
                    .apiKey(aiClientApiVO.getApiKey())
                    .completionsPath(aiClientApiVO.getCompletionsPath())
                    .embeddingsPath(aiClientApiVO.getEmbeddingsPath())
                    .build();
            apiObjectMap.put(aiClientApiVO.getApiId(), openAiApi);

            // 向 Spring 容器注册：保证执行阶段可以按 beanName 获取 ChatClient 依赖
            registerBean(beanName(aiClientApiVO.getApiId()), OpenAiApi.class, openAiApi);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
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

}
