package cn.ethan.ai.domain.agent.service.armory;

import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.ArmoryAssemblyObjectKeyEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientModelVO;
import cn.ethan.ai.domain.agent.model.valobj.ArmoryAssemblyContextVO;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话模型节点配置
 */
@Slf4j
@Service
public class AiClientModelNode extends AbstractArmorySupport {

    private static final int MIN_REQUEST_TIMEOUT_SECONDS = 10;

    private static final int MAX_REQUEST_TIMEOUT_SECONDS = 300;

    private static final int MAX_MODEL_RETRIES = 2;

    @Resource
    private AiClientNode aiClientNode;

    @Value("${ai-agent.model.request-timeout-seconds:60}")
    private int requestTimeoutSeconds;

    @Value("${ai-agent.model.max-retries:1}")
    private int maxRetries;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, ArmoryAssemblyContextVO assemblyContext) throws Exception {
        log.info("智能体装配节点，对话模型配置：{}", JSON.toJSONString(requestParameter));

        List<AiClientModelVO> aiClientModelList = assemblyContext.getValue(dataName());

        if (aiClientModelList == null || aiClientModelList.isEmpty()) {
            log.warn("没有需要初始化的对话模型配置");
            return router(requestParameter, assemblyContext);
        }

        Map<String, OpenAiChatOptions> apiOptionsMap = assemblyContext.getValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_API_OPTIONS_MAP_KEY.getCode());
        Map<String, OpenAiChatModel> modelObjectMap = assemblyContext.getValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_MODEL_OBJECT_MAP_KEY.getCode());
        if (modelObjectMap == null) {
            modelObjectMap = new HashMap<>();
            assemblyContext.setValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_MODEL_OBJECT_MAP_KEY.getCode(), modelObjectMap);
        }

        for (AiClientModelVO modelVO : aiClientModelList) {

            if (apiOptionsMap == null) {
                throw new RuntimeException("模型接口选项为空，无法装配对话模型");
            }

            OpenAiChatOptions apiOptions = apiOptionsMap.get(modelVO.getApiId());
            if (null == apiOptions) {
                throw new RuntimeException("模型关联的接口选项不存在，modelId=" + modelVO.getModelId() + "，apiId=" + modelVO.getApiId());
            }

            // 实例化对话模型，其他模型可通过 OpenAI 兼容接口接入。
            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .options(apiOptions.mutate()
                            .model(modelVO.getModelName())
                            .maxRetries(resolveMaxRetries(maxRetries))
                            .build())
                    .httpClientBuilderCustomizer(builder -> builder.timeout(resolveRequestTimeout(requestTimeoutSeconds)))
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
        return aiClientNode;
    }

    @Override
    protected String beanName(String beanId) {
        return AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_MODEL.getDataName();
    }

    static Duration resolveRequestTimeout(int seconds) {
        return Duration.ofSeconds(Math.max(MIN_REQUEST_TIMEOUT_SECONDS,
                Math.min(MAX_REQUEST_TIMEOUT_SECONDS, seconds)));
    }

    static int resolveMaxRetries(int retries) {
        return Math.max(0, Math.min(MAX_MODEL_RETRIES, retries));
    }

}
