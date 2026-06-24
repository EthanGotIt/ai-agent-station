package cn.ethan.ai.domain.agent.service.armory.business.data.impl;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiClientApiVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientModelVO;
import cn.ethan.ai.domain.agent.model.valobj.ArmoryAssemblyContextVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.service.armory.business.data.ILoadDataStrategy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 按对话模型维度加载装配配置。
 */
@Slf4j
@Service("aiClientModelLoadDataStrategy")
public class AiClientModelLoadDataStrategy implements ILoadDataStrategy {

    @Resource
    private IAgentRepository repository;

    @Resource
    protected ThreadPoolExecutor threadPoolExecutor;

    @Override
    public void loadData(ArmoryCommandEntity armoryCommandEntity, ArmoryAssemblyContextVO assemblyContext) {
        List<String> modelIdList = armoryCommandEntity.getCommandIdList();

        CompletableFuture<List<AiClientApiVO>> aiClientApiListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询模型接口配置，模型ID：{}", modelIdList);

            return repository.queryAiClientApiVOListByModelIds(modelIdList);
        }, threadPoolExecutor);

        CompletableFuture<List<AiClientModelVO>> aiClientModelListFuture = CompletableFuture.supplyAsync(() -> {
            log.info("查询对话模型配置，模型ID：{}", modelIdList);

            return repository.queryAiClientModelVOByModelIds(modelIdList);
        }, threadPoolExecutor);

        CompletableFuture.allOf(aiClientApiListFuture, aiClientModelListFuture).thenRun(() -> {
            assemblyContext.setValue(AiAgentEnumVO.AI_CLIENT_API.getDataName(), aiClientApiListFuture.join());
            assemblyContext.setValue(AiAgentEnumVO.AI_CLIENT_MODEL.getDataName(), aiClientModelListFuture.join());
        }).join();
    }

}
