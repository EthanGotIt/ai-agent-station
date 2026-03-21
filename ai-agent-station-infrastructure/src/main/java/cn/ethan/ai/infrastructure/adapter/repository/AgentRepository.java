package cn.ethan.ai.infrastructure.adapter.repository;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.valobj.*;
import cn.ethan.ai.infrastructure.dao.*;
import cn.ethan.ai.infrastructure.dao.po.*;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.*;

import static cn.ethan.ai.domain.agent.model.valobj.AiAgentEnumVO.*;

/**
 * AiAgent 仓储服务
 */
@Slf4j
@Repository
public class AgentRepository implements IAgentRepository {

    @Resource
    private IAiAgentDao aiAgentDao;

    @Resource
    private IAiAgentFlowConfigDao aiAgentFlowConfigDao;

    @Resource
    private IAiAgentTaskScheduleDao aiAgentTaskScheduleDao;

    @Resource
    private IAiClientAdvisorDao aiClientAdvisorDao;

    @Resource
    private IAiClientApiDao aiClientApiDao;

    @Resource
    private IAiClientConfigDao aiClientConfigDao;

    @Resource
    private IAiClientDao aiClientDao;

    @Resource
    private IAiClientModelDao aiClientModelDao;

    @Resource
    private IAiClientRagOrderDao aiClientRagOrderDao;

    @Resource
    private IAiClientSystemPromptDao aiClientSystemPromptDao;

    @Resource
    private IAiClientToolMcpDao aiClientToolMcpDao;

    @Override
    public List<AiClientApiVO> queryAiClientApiVOListByClientIds(List<String> clientIdList) {
        if (isEmpty(clientIdList)) {
            return List.of();
        }

        Set<String> modelIds = queryModelIdsByClientIds(clientIdList);
        return queryAiClientApiVOListByModelIds(new ArrayList<>(modelIds));
    }

    @Override
    public List<AiClientModelVO> queryAiClientModelVOByClientIds(List<String> clientIdList) {
        if (isEmpty(clientIdList)) {
            return List.of();
        }

        Map<String, AiClientModelVO> modelVOMap = new LinkedHashMap<>();
        Set<String> modelIds = queryModelIdsByClientIds(clientIdList);

        for (String modelId : modelIds) {
            AiClientModel model = aiClientModelDao.queryByModelId(modelId);
            if (model != null && model.getStatus() == 1) {
                AiClientModelVO modelVO = AiClientModelVO.builder()
                        .modelId(model.getModelId())
                        .apiId(model.getApiId())
                        .modelName(model.getModelName())
                        .modelType(model.getModelType())
                        .build();
                modelVO.setToolMcpIds(queryToolMcpIdsByModelId(modelId));
                modelVOMap.putIfAbsent(modelId, modelVO);
            }
        }

        return new ArrayList<>(modelVOMap.values());
    }

    @Override
    public List<AiClientToolMcpVO> queryAiClientToolMcpVOByClientIds(List<String> clientIdList) {
        if (isEmpty(clientIdList)) {
            return List.of();
        }

        Map<String, AiClientToolMcpVO> mcpVOMap = new LinkedHashMap<>();
        Set<String> modelIds = queryModelIdsByClientIds(clientIdList);

        for (String modelId : modelIds) {
            for (String mcpId : queryToolMcpIdsByModelId(modelId)) {
                if (mcpVOMap.containsKey(mcpId)) {
                    continue;
                }

                AiClientToolMcp toolMcp = aiClientToolMcpDao.queryByMcpId(mcpId);
                if (toolMcp != null && toolMcp.getStatus() == 1) {
                    AiClientToolMcpVO mcpVO = AiClientToolMcpVO.builder()
                            .mcpId(toolMcp.getMcpId())
                            .mcpName(toolMcp.getMcpName())
                            .transportType(toolMcp.getTransportType())
                            .transportConfig(toolMcp.getTransportConfig())
                            .requestTimeout(toolMcp.getRequestTimeout())
                            .build();
                    parseTransportConfig(mcpVO, toolMcp.getTransportType(), toolMcp.getTransportConfig());
                    mcpVOMap.put(mcpId, mcpVO);
                }
            }
        }

        return new ArrayList<>(mcpVOMap.values());
    }

    @Override
    public List<AiClientSystemPromptVO> queryAiClientSystemPromptVOByClientIds(List<String> clientIdList) {
        if (isEmpty(clientIdList)) {
            return List.of();
        }

        Map<String, AiClientSystemPromptVO> promptVOMap = new LinkedHashMap<>();

        for (String clientId : clientIdList) {
            List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT.getCode(), clientId);
            for (AiClientConfig config : configs) {
                if (!"prompt".equals(config.getTargetType()) || config.getStatus() != 1) {
                    continue;
                }

                String promptId = config.getTargetId();
                if (promptVOMap.containsKey(promptId)) {
                    continue;
                }

                AiClientSystemPrompt systemPrompt = aiClientSystemPromptDao.queryByPromptId(promptId);
                if (systemPrompt != null && systemPrompt.getStatus() == 1) {
                    promptVOMap.put(promptId, AiClientSystemPromptVO.builder()
                            .promptId(systemPrompt.getPromptId())
                            .promptName(systemPrompt.getPromptName())
                            .promptContent(systemPrompt.getPromptContent())
                            .description(systemPrompt.getDescription())
                            .build());
                }
            }
        }

        return new ArrayList<>(promptVOMap.values());
    }

    @Override
    public List<AiClientAdvisorVO> queryAiClientAdvisorVOByClientIds(List<String> clientIdList) {
        if (isEmpty(clientIdList)) {
            return List.of();
        }

        Map<String, AiClientAdvisorVO> advisorVOMap = new LinkedHashMap<>();

        for (String clientId : clientIdList) {
            List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT.getCode(), clientId);
            for (AiClientConfig config : configs) {
                if (config.getStatus() != 1 || !"advisor".equals(config.getTargetType())) {
                    continue;
                }

                String advisorId = config.getTargetId();
                if (advisorVOMap.containsKey(advisorId)) {
                    continue;
                }

                AiClientAdvisor advisor = aiClientAdvisorDao.queryByAdvisorId(advisorId);
                if (advisor == null || advisor.getStatus() != 1) {
                    continue;
                }

                advisorVOMap.put(advisorId, buildAdvisorVO(advisor));
            }
        }

        return new ArrayList<>(advisorVOMap.values());
    }

    private AiClientAdvisorVO buildAdvisorVO(AiClientAdvisor advisor) {
        AiClientAdvisorVO.ChatMemory chatMemory = null;
        AiClientAdvisorVO.RagAnswer ragAnswer = null;

        String extParam = advisor.getExtParam();
        if (extParam != null && !extParam.trim().isEmpty()) {
            try {
                if ("ChatMemory".equals(advisor.getAdvisorType())) {
                    chatMemory = JSON.parseObject(extParam, AiClientAdvisorVO.ChatMemory.class);
                } else if ("RagAnswer".equals(advisor.getAdvisorType())) {
                    ragAnswer = JSON.parseObject(extParam, AiClientAdvisorVO.RagAnswer.class);
                }
            } catch (Exception ignored) {
            }
        }

        return AiClientAdvisorVO.builder()
                .advisorId(advisor.getAdvisorId())
                .advisorName(advisor.getAdvisorName())
                .advisorType(advisor.getAdvisorType())
                .orderNum(advisor.getOrderNum())
                .chatMemory(chatMemory)
                .ragAnswer(ragAnswer)
                .build();
    }

    @Override
    public List<AiClientVO> queryAiClientVOByClientIds(List<String> clientIdList) {
        if (isEmpty(clientIdList)) {
            return List.of();
        }

        List<AiClientVO> result = new ArrayList<>();
        Set<String> processedIds = new HashSet<>();

        for (String clientId : clientIdList) {
            if (!processedIds.add(clientId)) {
                continue;
            }

            AiClient aiClient = aiClientDao.queryByClientId(clientId);
            if (aiClient == null || aiClient.getStatus() != 1) {
                continue;
            }

            result.add(buildClientVO(clientId, aiClient));
        }

        return result;
    }

    private AiClientVO buildClientVO(String clientId, AiClient aiClient) {
        List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT.getCode(), clientId);

        String modelId = null;
        List<String> promptIdList = new ArrayList<>();
        List<String> mcpIdList = new ArrayList<>();
        List<String> advisorIdList = new ArrayList<>();

        for (AiClientConfig config : configs) {
            if (config.getStatus() != 1) {
                continue;
            }
            switch (config.getTargetType()) {
                case "model" -> modelId = config.getTargetId();
                case "prompt" -> promptIdList.add(config.getTargetId());
                case "tool_mcp" -> mcpIdList.add(config.getTargetId());
                case "advisor" -> advisorIdList.add(config.getTargetId());
            }
        }

        return AiClientVO.builder()
                .clientId(aiClient.getClientId())
                .clientName(aiClient.getClientName())
                .description(aiClient.getDescription())
                .modelId(modelId)
                .promptIdList(promptIdList)
                .mcpIdList(mcpIdList)
                .advisorIdList(advisorIdList)
                .build();
    }

    @Override
    public List<AiClientApiVO> queryAiClientApiVOListByModelIds(List<String> modelIdList) {
        if (isEmpty(modelIdList)) {
            return List.of();
        }

        Map<String, AiClientApiVO> apiVOMap = new LinkedHashMap<>();

        for (String modelId : modelIdList) {
            AiClientModel model = aiClientModelDao.queryByModelId(modelId);
            if (model == null || model.getStatus() != 1) {
                continue;
            }

            AiClientApi apiConfig = aiClientApiDao.queryByApiId(model.getApiId());
            if (apiConfig != null && apiConfig.getStatus() == 1) {
                apiVOMap.putIfAbsent(apiConfig.getApiId(),
                        AiClientApiVO.builder()
                                .apiId(apiConfig.getApiId())
                                .baseUrl(apiConfig.getBaseUrl())
                                .apiKey(apiConfig.getApiKey())
                                .completionsPath(apiConfig.getCompletionsPath())
                                .embeddingsPath(apiConfig.getEmbeddingsPath())
                                .build());
            }
        }

        return new ArrayList<>(apiVOMap.values());
    }

    @Override
    public List<AiClientModelVO> queryAiClientModelVOByModelIds(List<String> modelIdList) {
        if (isEmpty(modelIdList)) {
            return List.of();
        }

        Map<String, AiClientModelVO> modelVOMap = new LinkedHashMap<>();

        for (String modelId : modelIdList) {
            AiClientModel model = aiClientModelDao.queryByModelId(modelId);
            if (model != null && model.getStatus() == 1) {
                modelVOMap.putIfAbsent(modelId,
                        AiClientModelVO.builder()
                                .modelId(model.getModelId())
                                .apiId(model.getApiId())
                                .modelName(model.getModelName())
                                .modelType(model.getModelType())
                                .build());
            }
        }

        return new ArrayList<>(modelVOMap.values());
    }

    /**
     * 判断列表是否为空
     */
    private boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    /**
     * 通过clientId查询关联的model id列表
     */
    private Set<String> queryModelIdsByClientIds(List<String> clientIdList) {
        Set<String> modelIds = new HashSet<>();
        for (String clientId : clientIdList) {
            List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT.getCode(), clientId);
            configs.stream()
                    .filter(c -> AI_CLIENT_MODEL.getCode().equals(c.getTargetType()) && c.getStatus() == 1)
                    .map(AiClientConfig::getTargetId)
                    .forEach(modelIds::add);
        }

        return modelIds;
    }

    /**
     * 通过modelId查询关联的tool_mcp id列表
     */
    private List<String> queryToolMcpIdsByModelId(String modelId) {
        List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT_MODEL.getCode(), modelId);

        return configs.stream()
                .filter(c -> AI_CLIENT_TOOL_MCP.getCode().equals(c.getTargetType()) && c.getStatus() == 1)
                .map(AiClientConfig::getTargetId)
                .toList();
    }

    /**
     * 解析MCP传输配置(sse/stdio)
     */
    private void parseTransportConfig(AiClientToolMcpVO mcpVO, String transportType, String transportConfig) {
        try {
            if ("sse".equals(transportType)) {
                ObjectMapper objectMapper = new ObjectMapper();
                mcpVO.setTransportConfigSse(objectMapper.readValue(transportConfig, AiClientToolMcpVO.TransportConfigSse.class));
            } else if ("stdio".equals(transportType)) {
                Map<String, Object> jsonMap = JSON.parseObject(transportConfig, new TypeReference<Map<String, Object>>() {});
                String firstKey = jsonMap.keySet().iterator().next();
                Object innerConfig = jsonMap.get(firstKey);
                AiClientToolMcpVO.TransportConfigStdio transportConfigStdio = JSON.parseObject(JSON.toJSONString(innerConfig), AiClientToolMcpVO.TransportConfigStdio.class);
                mcpVO.setTransportConfigStdio(transportConfigStdio);
            }
        } catch (Exception e) {
            log.error("解析传输配置失败: {}", e.getMessage(), e);
        }
    }

}
