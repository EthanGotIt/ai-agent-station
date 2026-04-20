package cn.ethan.ai.domain.agent.service.armory;

import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientAdvisorTypeEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.ArmoryAssemblyObjectKeyEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientAdvisorVO;
import cn.ethan.ai.domain.agent.model.valobj.ArmoryAssemblyContextVO;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 顾问角色节点
 */
@Slf4j
@Service
public class AiClientAdvisorNode extends AbstractArmorySupport {

    @Resource
    private ObjectProvider<VectorStore> vectorStoreProvider;

    @Resource
    private AiClientNode aiClientNode;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, ArmoryAssemblyContextVO assemblyContext) throws Exception {
        log.info("智能体装配节点，顾问配置：{}", JSON.toJSONString(requestParameter));

        List<AiClientAdvisorVO> aiClientAdvisorList = assemblyContext.getValue(dataName());

        if (aiClientAdvisorList == null || aiClientAdvisorList.isEmpty()) {
            log.warn("没有需要初始化的顾问配置");
            return router(requestParameter, assemblyContext);
        }

        Map<String, Advisor> advisorObjectMap = assemblyContext.getValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_ADVISOR_OBJECT_MAP_KEY.getCode());
        if (advisorObjectMap == null) {
            advisorObjectMap = new HashMap<>();
            assemblyContext.setValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_ADVISOR_OBJECT_MAP_KEY.getCode(), advisorObjectMap);
        }

        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        for (AiClientAdvisorVO aiClientAdvisorVO : aiClientAdvisorList) {
            AiClientAdvisorTypeEnumVO advisorTypeEnum = AiClientAdvisorTypeEnumVO.getByCode(aiClientAdvisorVO.getAdvisorType());
            if (advisorTypeEnum.isVectorStoreRequired() && vectorStore == null) {
                log.warn("顾问配置已跳过，原因：RAG 顾问需要启用向量库。advisorId：{}，advisorName：{}",
                        aiClientAdvisorVO.getAdvisorId(), aiClientAdvisorVO.getAdvisorName());
                continue;
            }

            Advisor advisor = createAdvisor(aiClientAdvisorVO, advisorTypeEnum, vectorStore);
            advisorObjectMap.put(aiClientAdvisorVO.getAdvisorId(), advisor);
            registerBean(beanName(aiClientAdvisorVO.getAdvisorId()), Advisor.class, advisor);
        }

        return router(requestParameter, assemblyContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, ArmoryAssemblyContextVO, String> get(ArmoryCommandEntity requestParameter, ArmoryAssemblyContextVO assemblyContext) throws Exception {
        return aiClientNode;
    }

    protected String beanName(String beanId) {
        return AiAgentEnumVO.AI_CLIENT_ADVISOR.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_ADVISOR.getDataName();
    }

    private Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, AiClientAdvisorTypeEnumVO advisorTypeEnum, VectorStore vectorStore) {
        return advisorTypeEnum.createAdvisor(aiClientAdvisorVO, vectorStore);
    }

}
