package cn.ethan.ai.domain.agent.service.armory;

import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.ArmoryAssemblyObjectKeyEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.ethan.ai.domain.agent.model.valobj.ArmoryAssemblyContextVO;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 客户端配置节点
 */
@Slf4j
@Service
public class AiClientToolMcpNode extends AbstractArmorySupport {

    private static final ObjectMapper MCP_OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private AiClientModelNode aiClientModelNode;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, ArmoryAssemblyContextVO assemblyContext) throws Exception {
        log.info("智能体装配节点，MCP 工具配置：{}", JSON.toJSONString(requestParameter));

        List<AiClientToolMcpVO> aiClientToolMcpList = assemblyContext.getValue(dataName());

        if (aiClientToolMcpList == null || aiClientToolMcpList.isEmpty()) {
            log.warn("没有需要初始化的 MCP 工具配置");
            return router(requestParameter, assemblyContext);
        }

        Map<String, McpSyncClient> mcpObjectMap = assemblyContext.getValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_TOOL_MCP_OBJECT_MAP_KEY.getCode());
        if (mcpObjectMap == null) {
            mcpObjectMap = new HashMap<>();
            assemblyContext.setValue(ArmoryAssemblyObjectKeyEnumVO.AI_CLIENT_TOOL_MCP_OBJECT_MAP_KEY.getCode(), mcpObjectMap);
        }

        for (AiClientToolMcpVO mcpVO : aiClientToolMcpList) {
            try {
                McpSyncClient mcpSyncClient = createMcpSyncClient(mcpVO);
                mcpObjectMap.put(mcpVO.getMcpId(), mcpSyncClient);

                // 向 Spring 容器注册：执行阶段可按名称获取 MCP 客户端。
                registerBean(beanName(mcpVO.getMcpId()), McpSyncClient.class, mcpSyncClient);
            } catch (Exception e) {
                log.warn("MCP 工具初始化失败，已跳过该工具，mcpId：{}，mcpName：{}，传输协议：{}，原因：{}",
                        mcpVO.getMcpId(), mcpVO.getMcpName(), mcpVO.getTransportType(), e.getMessage());
                log.debug("MCP 工具初始化异常堆栈", e);
            }
        }

        return router(requestParameter, assemblyContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, ArmoryAssemblyContextVO, String> get(ArmoryCommandEntity requestParameter, ArmoryAssemblyContextVO assemblyContext) throws Exception {
        return aiClientModelNode;
    }

    @Override
    protected String beanName(String beanId) {
        return AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getDataName();
    }

    private McpSyncClient createMcpSyncClient(AiClientToolMcpVO aiClientToolMcpVO) {
        String transportType = aiClientToolMcpVO.getTransportType();

        switch (transportType) {
            case "stdio" -> {
                AiClientToolMcpVO.TransportConfigStdio transportConfigStdio = aiClientToolMcpVO.getTransportConfigStdio();
                Duration timeout = resolveTimeout(aiClientToolMcpVO);

                var stdioParams = ServerParameters.builder(transportConfigStdio.getCommand())
                        .args(transportConfigStdio.getArgs())
                        .env(transportConfigStdio.getEnv())
                        .build();

                var mcpClient = McpClient.sync(new StdioClientTransport(stdioParams, new JacksonMcpJsonMapper(MCP_OBJECT_MAPPER)))
                        .requestTimeout(timeout)
                        .initializationTimeout(timeout)
                        .build();
                mcpClient.initialize();

                log.info("Stdio MCP 客户端初始化完成，mcpId：{}，mcpName：{}",
                        aiClientToolMcpVO.getMcpId(), aiClientToolMcpVO.getMcpName());
                return mcpClient;
            }
            case "streamable_http" -> {
                AiClientToolMcpVO.TransportConfigStreamableHttp transportConfig = aiClientToolMcpVO.getTransportConfigStreamableHttp();
                Duration timeout = resolveTimeout(aiClientToolMcpVO);

                if (transportConfig == null || StringUtils.isBlank(transportConfig.getBaseUri())) {
                    throw new RuntimeException("Streamable HTTP MCP 配置缺少 baseUri，mcpId：" + aiClientToolMcpVO.getMcpId());
                }
                ParsedHttpAddress parsedStreamableAddress = parseHttpAddress(transportConfig.getBaseUri(), transportConfig.getEndpoint());

                HttpClientStreamableHttpTransport.Builder streamableBuilder = HttpClientStreamableHttpTransport
                        .builder(parsedStreamableAddress.baseUri())
                        .endpoint(parsedStreamableAddress.endpoint());

                Map<String, String> streamableHeaders = transportConfig.getHeaders();
                if (streamableHeaders != null && !streamableHeaders.isEmpty()) {
                    streamableBuilder.customizeRequest(requestBuilder -> applyHeaders(requestBuilder, streamableHeaders));
                }

                HttpClientStreamableHttpTransport streamableHttpTransport = streamableBuilder
                        .build();

                McpSyncClient mcpSyncClient = McpClient.sync(streamableHttpTransport)
                        .requestTimeout(timeout)
                        .initializationTimeout(timeout)
                        .build();
                mcpSyncClient.initialize();

                log.info("Streamable HTTP MCP 客户端初始化完成，mcpId：{}，mcpName：{}",
                        aiClientToolMcpVO.getMcpId(), aiClientToolMcpVO.getMcpName());
                return mcpSyncClient;
            }
        }

        throw new RuntimeException("不支持的 MCP 传输协议：" + transportType);
    }

    private Duration resolveTimeout(AiClientToolMcpVO aiClientToolMcpVO) {
        Integer requestTimeout = aiClientToolMcpVO.getRequestTimeout();
        if (requestTimeout == null || requestTimeout <= 0) {
            return Duration.ofMinutes(3);
        }
        return Duration.ofMinutes(requestTimeout);
    }

    private void applyHeaders(HttpRequest.Builder requestBuilder, Map<String, String> headers) {
        headers.forEach((key, value) -> {
            if (StringUtils.isNotBlank(key) && StringUtils.isNotBlank(value)) {
                requestBuilder.setHeader(key.trim(), value.trim());
            }
        });
    }

    private ParsedHttpAddress parseHttpAddress(String originalBaseUri, String configuredEndpoint) {
        String baseUri = originalBaseUri.trim();
        String endpoint = configuredEndpoint;

        try {
            URI uri = URI.create(baseUri);
            if (StringUtils.isNotBlank(uri.getScheme()) && StringUtils.isNotBlank(uri.getAuthority())) {
                baseUri = uri.getScheme() + "://" + uri.getAuthority();
            }
            if (StringUtils.isBlank(endpoint)) {
                String path = uri.getRawPath();
                endpoint = (StringUtils.isBlank(path) || "/".equals(path)) ? "/mcp" : path;
                if (StringUtils.isNotBlank(uri.getRawQuery())) {
                    endpoint = endpoint + "?" + uri.getRawQuery();
                }
            }
        } catch (Exception ignore) {
            if (StringUtils.isBlank(endpoint)) {
                endpoint = "/mcp";
            }
        }

        endpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return new ParsedHttpAddress(baseUri, endpoint);
    }

    private record ParsedHttpAddress(String baseUri, String endpoint) {
    }

}
