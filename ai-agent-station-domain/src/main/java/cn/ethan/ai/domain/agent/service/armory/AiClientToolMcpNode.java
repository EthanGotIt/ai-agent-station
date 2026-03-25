package cn.ethan.ai.domain.agent.service.armory;

import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.ethan.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MCP客户端配置节点
 */
@Slf4j
@Service
public class AiClientToolMcpNode extends AbstractArmorySupport {

    @Resource
    private AiClientModelNode aiClientModelNode;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，Tool MCP 工具配置{}", JSON.toJSONString(requestParameter));

        List<AiClientToolMcpVO> aiClientToolMcpList = dynamicContext.getValue(dataName());

        if (aiClientToolMcpList == null || aiClientToolMcpList.isEmpty()) {
            log.warn("没有需要被初始化的 ai client tool mcp");
            return router(requestParameter, dynamicContext);
        }

        for (AiClientToolMcpVO mcpVO : aiClientToolMcpList) {
            McpSyncClient mcpSyncClient = createMcpSyncClient(mcpVO);
            registerBean(beanName(mcpVO.getMcpId()), McpSyncClient.class, mcpSyncClient);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
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

                var stdioParams = ServerParameters.builder(transportConfigStdio.getCommand())
                        .args(transportConfigStdio.getArgs())
                        .env(transportConfigStdio.getEnv())
                        .build();

                var mcpClient = McpClient.sync(new StdioClientTransport(stdioParams, McpJsonMapper.getDefault()))
                        .requestTimeout(resolveRequestTimeout(aiClientToolMcpVO)).build();
                var init_stdio = mcpClient.initialize();

                log.info("Tool Stdio MCP Initialized {}", init_stdio);
                return mcpClient;
            }
            case "sse" -> {
                AiClientToolMcpVO.TransportConfigSse transportConfigSse = aiClientToolMcpVO.getTransportConfigSse();
                if (transportConfigSse == null || StringUtils.isBlank(transportConfigSse.getBaseUri())) {
                    throw new RuntimeException("err! sse transportConfig/baseUri is null, mcpId:" + aiClientToolMcpVO.getMcpId());
                }
                ParsedHttpAddress parsedSseAddress = parseHttpAddress(transportConfigSse.getBaseUri(), transportConfigSse.getSseEndpoint(), "/sse");

                HttpClientSseClientTransport.Builder sseBuilder = HttpClientSseClientTransport
                        .builder(parsedSseAddress.baseUri()).sseEndpoint(parsedSseAddress.endpoint());
                Map<String, String> sseHeaders = transportConfigSse.getHeaders();
                if (sseHeaders != null && !sseHeaders.isEmpty()) {
                    sseBuilder.customizeRequest(requestBuilder -> applyHeaders(requestBuilder, sseHeaders));
                }

                HttpClientSseClientTransport sseClientTransport = sseBuilder
                        .build();

                McpSyncClient mcpSyncClient = McpClient.sync(sseClientTransport).requestTimeout(resolveRequestTimeout(aiClientToolMcpVO)).build();
                var init_sse = mcpSyncClient.initialize();

                log.info("Tool SSE MCP Initialized {}", init_sse);
                return mcpSyncClient;
            }
            case "streamable_http" -> {
                AiClientToolMcpVO.TransportConfigStreamableHttp transportConfig = aiClientToolMcpVO.getTransportConfigStreamableHttp();

                if (transportConfig == null || StringUtils.isBlank(transportConfig.getBaseUri())) {
                    throw new RuntimeException("err! streamable_http transportConfig/baseUri is null, mcpId:" + aiClientToolMcpVO.getMcpId());
                }
                ParsedHttpAddress parsedStreamableAddress = parseHttpAddress(transportConfig.getBaseUri(), transportConfig.getEndpoint(), "/mcp");

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
                        .requestTimeout(resolveRequestTimeout(aiClientToolMcpVO))
                        .build();
                var init_streamable = mcpSyncClient.initialize();

                log.info("Tool Streamable HTTP MCP Initialized, mcpId:{}, baseUri:{}, endpoint:{}, result:{}",
                        aiClientToolMcpVO.getMcpId(), parsedStreamableAddress.baseUri(), parsedStreamableAddress.endpoint(), init_streamable);
                return mcpSyncClient;
            }
        }

        throw new RuntimeException("err! transportType " + transportType + " not exist!");
    }

    private Duration resolveRequestTimeout(AiClientToolMcpVO aiClientToolMcpVO) {
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

    private ParsedHttpAddress parseHttpAddress(String originalBaseUri, String configuredEndpoint, String defaultEndpoint) {
        String baseUri = originalBaseUri.trim();
        String endpoint = configuredEndpoint;

        try {
            URI uri = URI.create(baseUri);
            if (StringUtils.isNotBlank(uri.getScheme()) && StringUtils.isNotBlank(uri.getAuthority())) {
                baseUri = uri.getScheme() + "://" + uri.getAuthority();
            }
            if (StringUtils.isBlank(endpoint)) {
                String path = uri.getRawPath();
                endpoint = (StringUtils.isBlank(path) || "/".equals(path)) ? defaultEndpoint : path;
                if (StringUtils.isNotBlank(uri.getRawQuery())) {
                    endpoint = endpoint + "?" + uri.getRawQuery();
                }
            }
        } catch (Exception ignore) {
            if (StringUtils.isBlank(endpoint)) {
                endpoint = defaultEndpoint;
            }
        }

        endpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return new ParsedHttpAddress(baseUri, endpoint);
    }

    private record ParsedHttpAddress(String baseUri, String endpoint) {
    }

}
