package cn.ethan.ai.domain.agent.adapter.repository;

import cn.ethan.ai.domain.agent.model.valobj.*;

import java.util.List;

/**
 * AiAgent 仓储接口
 */
public interface IAgentRepository {

    List<AiClientApiVO> queryAiClientApiVOListByClientIds(List<String> clientIdList);

    List<AiClientModelVO> queryAiClientModelVOByClientIds(List<String> clientIdList);

    List<AiClientToolMcpVO> queryAiClientToolMcpVOByClientIds(List<String> clientIdList);

    List<AiClientSystemPromptVO> queryAiClientSystemPromptVOByClientIds(List<String> clientIdList);

    List<AiClientAdvisorVO> queryAiClientAdvisorVOByClientIds(List<String> clientIdList);

    List<AiClientVO> queryAiClientVOByClientIds(List<String> clientIdList);

    List<AiClientApiVO> queryAiClientApiVOListByModelIds(List<String> modelIdList);

    List<AiClientModelVO> queryAiClientModelVOByModelIds(List<String> modelIdList);

}
