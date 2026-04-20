package cn.ethan.ai.infrastructure.adapter.repository;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.valobj.*;
import cn.ethan.ai.infrastructure.dao.*;
import cn.ethan.ai.infrastructure.dao.po.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Repository;

import java.util.*;

import static cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO.*;

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
    private IAiClientSystemPromptDao aiClientSystemPromptDao;

    @Resource
    private IAiClientToolMcpDao aiClientToolMcpDao;

    @Resource
    private Environment environment;

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
                    if (shouldSkipMcpConfig(mcpVO)) {
                        continue;
                    }
                    mcpVOMap.put(mcpId, mcpVO);
                }
            }
        }

        return new ArrayList<>(mcpVOMap.values());
    }

    @Override
    public Map<String, AiClientSystemPromptVO> queryAiClientSystemPromptVOByClientIds(List<String> clientIdList) {
        if (isEmpty(clientIdList)) {
            return Map.of();
        }

        Map<String, AiClientSystemPromptVO> promptVOMap = new LinkedHashMap<>();
        List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndIds(AI_CLIENT.getCode(), clientIdList);
        Set<String> promptIdSet = new LinkedHashSet<>();
        for (AiClientConfig config : configs) {
            if (!"prompt".equals(config.getTargetType()) || config.getStatus() != 1) {
                continue;
            }
            promptIdSet.add(config.getTargetId());
        }

        for (String promptId : promptIdSet) {
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

        return promptVOMap;
    }

    @Override
    public List<AiClientAdvisorVO> queryAiClientAdvisorVOByClientIds(List<String> clientIdList) {
        if (isEmpty(clientIdList)) {
            return List.of();
        }

        Map<String, AiClientAdvisorVO> advisorVOMap = new LinkedHashMap<>();
        List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndIds(AI_CLIENT.getCode(), clientIdList);
        Set<String> advisorIdSet = new LinkedHashSet<>();
        for (AiClientConfig config : configs) {
            if (config.getStatus() != 1 || !"advisor".equals(config.getTargetType())) {
                continue;
            }
            advisorIdSet.add(config.getTargetId());
        }

        for (String advisorId : advisorIdSet) {
            AiClientAdvisor advisor = aiClientAdvisorDao.queryByAdvisorId(advisorId);
            if (advisor == null || advisor.getStatus() != 1) {
                continue;
            }
            advisorVOMap.put(advisorId, buildAdvisorVO(advisor));
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
                    chatMemory = new ObjectMapper().readValue(extParam, AiClientAdvisorVO.ChatMemory.class);
                } else if ("RagAnswer".equals(advisor.getAdvisorType())) {
                    ragAnswer = new ObjectMapper().readValue(extParam, AiClientAdvisorVO.RagAnswer.class);
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
                                .baseUrl(resolveConfigValue(apiConfig.getBaseUrl()))
                                .apiKey(resolveConfigValue(apiConfig.getApiKey()))
                                .completionsPath(resolveConfigValue(apiConfig.getCompletionsPath()))
                                .embeddingsPath(resolveConfigValue(apiConfig.getEmbeddingsPath()))
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

    @Override
    public Map<String, AiAgentClientFlowConfigVO> queryAiAgentClientFlowConfig(String aiAgentId) {
        if (aiAgentId == null || aiAgentId.trim().isEmpty()) {
            return Map.of();
        }

        try {
            List<AiAgentFlowConfig> flowConfigs = aiAgentFlowConfigDao.queryByAgentId(aiAgentId);
            if (isEmpty(flowConfigs)) {
                return Map.of();
            }

            Map<String, AiAgentClientFlowConfigVO> result = new LinkedHashMap<>();
            for (AiAgentFlowConfig config : flowConfigs) {
                result.putIfAbsent(config.getClientType(),
                        AiAgentClientFlowConfigVO.builder()
                                .clientId(config.getClientId())
                                .clientName(config.getClientName())
                                .clientType(config.getClientType())
                                .sequence(config.getSequence())
                                .stepPrompt(config.getStepPrompt())
                                .build());
            }
            return result;
        } catch (Exception e) {
            log.error("Query ai agent client flow config failed, aiAgentId: {}", aiAgentId, e);
            return Map.of();
        }
    }

    @Override
    public AiAgentVO queryAiAgentByAgentId(String aiAgentId) {
        AiAgent aiAgent = aiAgentDao.queryByAgentId(aiAgentId);
        if (aiAgent == null) {
            return null;
        }

        return AiAgentVO.builder()
                .agentId(aiAgent.getAgentId())
                .agentName(aiAgent.getAgentName())
                .description(aiAgent.getDescription())
                .channel(aiAgent.getChannel())
                .status(aiAgent.getStatus())
                .build();
    }

    @Override
    public List<AiAgentClientFlowConfigVO> queryAiAgentClientsByAgentId(String aiAgentId) {
        List<AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOS = new ArrayList<>();

        List<AiAgentFlowConfig> flowConfigs = aiAgentFlowConfigDao.queryByAgentId(aiAgentId);
        for (AiAgentFlowConfig flowConfig : flowConfigs) {
            AiAgentClientFlowConfigVO configVO = AiAgentClientFlowConfigVO.builder()
                    .clientId(flowConfig.getClientId())
                    .clientName(flowConfig.getClientName())
                    .clientType(flowConfig.getClientType())
                    .sequence(flowConfig.getSequence())
                    .stepPrompt(flowConfig.getStepPrompt())
                    .build();

            aiAgentClientFlowConfigVOS.add(configVO);
        }

        return aiAgentClientFlowConfigVOS;
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
        List<AiClientConfig> configs = aiClientConfigDao.queryBySourceTypeAndIds(AI_CLIENT.getCode(), clientIdList);
        configs.stream()
                .filter(c -> AI_CLIENT_MODEL.getCode().equals(c.getTargetType()) && c.getStatus() == 1)
                .map(AiClientConfig::getTargetId)
                .forEach(modelIds::add);

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
     * 解析MCP传输配置(stdio/streamable_http)
     */
    private void parseTransportConfig(AiClientToolMcpVO mcpVO, String transportType, String transportConfig) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            String resolvedTransportConfig = resolveConfigValue(transportConfig);
            if ("stdio".equals(transportType)) {
                JsonNode rootNode = objectMapper.readTree(resolvedTransportConfig);
                JsonNode stdioNode = rootNode.has("command")
                        ? rootNode
                        : (rootNode.size() == 1 ? rootNode.elements().next() : rootNode);
                AiClientToolMcpVO.TransportConfigStdio transportConfigStdio = objectMapper.convertValue(stdioNode, AiClientToolMcpVO.TransportConfigStdio.class);
                mcpVO.setTransportConfigStdio(transportConfigStdio);
                mcpVO.setToolNames(transportConfigStdio.getToolNames());
            } else if ("streamable_http".equals(transportType)) {
                AiClientToolMcpVO.TransportConfigStreamableHttp transportConfigStreamableHttp =
                        objectMapper.readValue(resolvedTransportConfig, AiClientToolMcpVO.TransportConfigStreamableHttp.class);
                mcpVO.setTransportConfigStreamableHttp(transportConfigStreamableHttp);
                mcpVO.setToolNames(transportConfigStreamableHttp.getToolNames());
            }
        } catch (Exception e) {
            throw new RuntimeException("解析传输配置失败, mcpId:" + mcpVO.getMcpId() + ", transportType:" + transportType, e);
        }
    }

    private String resolveConfigValue(String value) {
        return ConfigPlaceholderResolver.resolve(value, environment);
    }

    private boolean shouldSkipMcpConfig(AiClientToolMcpVO mcpVO) {
        AiClientToolMcpVO.TransportConfigStreamableHttp streamableHttpConfig = mcpVO.getTransportConfigStreamableHttp();
        if (streamableHttpConfig == null || isEmpty(streamableHttpConfig.getRequiredHeaders())) {
            return false;
        }

        Map<String, String> headers = streamableHttpConfig.getHeaders();
        List<String> missingHeaders = streamableHttpConfig.getRequiredHeaders().stream()
                .filter(headerName -> isMissingRequiredHeader(headers, headerName))
                .toList();
        if (missingHeaders.isEmpty()) {
            return false;
        }

        log.info("MCP 工具配置缺少必要认证，本次跳过加载，mcpId：{}，mcpName：{}，缺失请求头：{}",
                mcpVO.getMcpId(), mcpVO.getMcpName(), missingHeaders);
        return true;
    }

    private boolean isMissingRequiredHeader(Map<String, String> headers, String headerName) {
        if (headerName == null || headerName.trim().isEmpty() || headers == null) {
            return true;
        }

        String headerValue = headers.get(headerName);
        if (headerValue == null) {
            return true;
        }

        String trimmedValue = headerValue.trim();
        if (trimmedValue.isEmpty() || trimmedValue.contains("${")) {
            return true;
        }

        return "authorization".equalsIgnoreCase(headerName.trim())
                && "bearer".equalsIgnoreCase(trimmedValue);
    }

}
