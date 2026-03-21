package cn.ethan.ai.infrastructure.adapter.repository;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.valobj.*;
import cn.ethan.ai.infrastructure.dao.*;
import cn.ethan.ai.infrastructure.dao.po.*;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.ethan.ai.domain.agent.model.valobj.AiAgentEnumVO.AI_CLIENT;
import static cn.ethan.ai.domain.agent.model.valobj.AiAgentEnumVO.AI_CLIENT_MODEL;

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

        Set<String> modelIds = getModelIdsByClientIds(clientIdList);
        return queryAiClientApiVOListByModelIds(new ArrayList<>(modelIds));
    }

    @Override
    public List<AiClientModelVO> queryAiClientModelVOByClientIds(List<String> clientIdList) {
        if (isEmpty(clientIdList)) {
            return List.of();
        }

        Set<String> modelIds = getModelIdsByClientIds(clientIdList);
        return queryAiClientModelVOByModelIds(new ArrayList<>(modelIds));
    }

    @Override
    public List<AiClientToolMcpVO> queryAiClientToolMcpVOByClientIds(List<String> clientIdList) {
        if (isEmpty(clientIdList)) {
            return List.of();
        }

        Map<String, AiClientToolMcpVO> result = new LinkedHashMap<>();

        for (String clientId : clientIdList) {
            List<AiClientConfig> configs = queryValidConfigsByClientId(clientId, "tool_mcp");
            for (AiClientConfig config : configs) {
                result.computeIfAbsent(config.getTargetId(), mcpId -> {
                    AiClientToolMcp toolMcp = aiClientToolMcpDao.queryByMcpId(mcpId);
                    if (toolMcp != null && toolMcp.getStatus() == 1) {
                        return AiClientToolMcpVO.builder()
                                .mcpId(toolMcp.getMcpId())
                                .mcpName(toolMcp.getMcpName())
                                .transportType(toolMcp.getTransportType())
                                .transportConfig(toolMcp.getTransportConfig())
                                .requestTimeout(toolMcp.getRequestTimeout())
                                .build();
                    }
                    return null;
                });
            }
        }

        return result.values().stream().filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public List<AiClientSystemPromptVO> queryAiClientSystemPromptVOByClientIds(List<String> clientIdList) {
        if (isEmpty(clientIdList)) {
            return List.of();
        }

        Map<String, AiClientSystemPromptVO> result = new LinkedHashMap<>();

        for (String clientId : clientIdList) {
            List<AiClientConfig> configs = queryValidConfigsByClientId(clientId, "prompt");
            for (AiClientConfig config : configs) {
                result.computeIfAbsent(config.getTargetId(), promptId -> {
                    AiClientSystemPrompt systemPrompt = aiClientSystemPromptDao.queryByPromptId(promptId);
                    if (systemPrompt != null && systemPrompt.getStatus() == 1) {
                        return AiClientSystemPromptVO.builder()
                                .promptId(systemPrompt.getPromptId())
                                .promptName(systemPrompt.getPromptName())
                                .promptContent(systemPrompt.getPromptContent())
                                .description(systemPrompt.getDescription())
                                .build();
                    }
                    return null;
                });
            }
        }

        return result.values().stream().filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public List<AiClientAdvisorVO> queryAiClientAdvisorVOByClientIds(List<String> clientIdList) {
        if (isEmpty(clientIdList)) {
            return List.of();
        }

        Map<String, AiClientAdvisorVO> result = new LinkedHashMap<>();

        for (String clientId : clientIdList) {
            List<AiClientConfig> configs = queryValidConfigsByClientId(clientId, "advisor");
            for (AiClientConfig config : configs) {
                result.computeIfAbsent(config.getTargetId(), advisorId -> {
                    AiClientAdvisor advisor = aiClientAdvisorDao.queryByAdvisorId(advisorId);
                    if (advisor != null && advisor.getStatus() == 1) {
                        return buildAiClientAdvisorVO(advisor);
                    }
                    return null;
                });
            }
        }

        return result.values().stream().filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public List<AiClientVO> queryAiClientVOByClientIds(List<String> clientIdList) {
        if (isEmpty(clientIdList)) {
            return List.of();
        }

        List<AiClientVO> result = new ArrayList<>();
        Set<String> processedClientIds = new HashSet<>();

        for (String clientId : clientIdList) {
            if (processedClientIds.contains(clientId)) {
                continue;
            }
            processedClientIds.add(clientId);

            AiClient aiClient = aiClientDao.queryByClientId(clientId);
            if (aiClient == null || aiClient.getStatus() != 1) {
                continue;
            }

            result.add(buildAiClientVO(aiClient));
        }

        return result;
    }

    @Override
    public List<AiClientApiVO> queryAiClientApiVOListByModelIds(List<String> modelIdList) {
        if (isEmpty(modelIdList)) {
            return List.of();
        }

        Map<String, AiClientApiVO> result = new LinkedHashMap<>();

        for (String modelId : modelIdList) {
            AiClientModel model = aiClientModelDao.queryByModelId(modelId);
            if (model == null || model.getStatus() != 1) {
                continue;
            }

            result.computeIfAbsent(model.getApiId(), apiId -> {
                AiClientApi apiConfig = aiClientApiDao.queryByApiId(apiId);
                if (apiConfig != null && apiConfig.getStatus() == 1) {
                    return AiClientApiVO.builder()
                            .apiId(apiConfig.getApiId())
                            .baseUrl(apiConfig.getBaseUrl())
                            .apiKey(apiConfig.getApiKey())
                            .completionsPath(apiConfig.getCompletionsPath())
                            .embeddingsPath(apiConfig.getEmbeddingsPath())
                            .build();
                }
                return null;
            });
        }

        return result.values().stream().filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public List<AiClientModelVO> queryAiClientModelVOByModelIds(List<String> modelIdList) {
        if (isEmpty(modelIdList)) {
            return List.of();
        }

        Map<String, AiClientModelVO> result = new LinkedHashMap<>();

        for (String modelId : modelIdList) {
            AiClientModel model = aiClientModelDao.queryByModelId(modelId);
            if (model != null && model.getStatus() == 1) {
                result.putIfAbsent(model.getModelId(), AiClientModelVO.builder()
                        .modelId(model.getModelId())
                        .apiId(model.getApiId())
                        .modelName(model.getModelName())
                        .modelType(model.getModelType())
                        .build());
            }
        }

        return new ArrayList<>(result.values());
    }

    private boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    private List<AiClientConfig> queryValidConfigsByClientId(String clientId, String targetType) {
        List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId(AI_CLIENT.getCode(), clientId);
        return configs.stream()
                .filter(config -> config.getStatus() == 1 && targetType.equals(config.getTargetType()))
                .collect(Collectors.toList());
    }

    private Set<String> getModelIdsByClientIds(List<String> clientIdList) {
        Set<String> modelIds = new HashSet<>();
        for (String clientId : clientIdList) {
            List<AiClientConfig> configs = queryValidConfigsByClientId(clientId, AI_CLIENT_MODEL.getCode());
            configs.stream()
                    .map(AiClientConfig::getTargetId)
                    .filter(Objects::nonNull)
                    .forEach(modelIds::add);
        }
        return modelIds;
    }

    private AiClientAdvisorVO buildAiClientAdvisorVO(AiClientAdvisor advisor) {
        AiClientAdvisorVO.ChatMemory chatMemory = null;
        AiClientAdvisorVO.RagAnswer ragAnswer = null;

        String extParam = advisor.getExtParam();
        if (extParam != null && !extParam.trim().isEmpty()) {
            try {
                if ("RagAnswer".equals(advisor.getAdvisorType())) {
                    ragAnswer = JSON.parseObject(extParam, AiClientAdvisorVO.RagAnswer.class);
                } else if ("ChatMemory".equals(advisor.getAdvisorType())) {
                    chatMemory = JSON.parseObject(extParam, AiClientAdvisorVO.ChatMemory.class);
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

    private AiClientVO buildAiClientVO(AiClient aiClient) {
        List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndId("client", aiClient.getClientId());

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

}
