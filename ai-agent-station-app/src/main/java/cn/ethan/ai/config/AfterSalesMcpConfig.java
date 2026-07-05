package cn.ethan.ai.config;

import cn.ethan.ai.infrastructure.adapter.mcp.AfterSalesMcpServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springaicommunity.mcp.provider.tool.SyncMcpToolProvider;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 售后 MCP Server 配置。
 *
 * <p>将 {@link AfterSalesMcpServer} 中的 {@link org.springaicommunity.mcp.annotation.McpTool}
 * 方法注册为 MCP 工具，并通过 HTTP SSE 暴露。
 */
@Configuration
public class AfterSalesMcpConfig {

    public static final String MCP_SSE_ENDPOINT = "/mcp/after-sales/sse";
    public static final String MCP_MESSAGE_ENDPOINT = "/mcp/after-sales/message";

    @Bean
    public SyncMcpToolProvider afterSalesMcpToolProvider(AfterSalesMcpServer afterSalesMcpServer) {
        return new SyncMcpToolProvider(List.of(afterSalesMcpServer));
    }

    @Bean
    public HttpServletSseServerTransportProvider afterSalesMcpTransportProvider() {
        return HttpServletSseServerTransportProvider.builder()
                .sseEndpoint(MCP_SSE_ENDPOINT)
                .messageEndpoint(MCP_MESSAGE_ENDPOINT)
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletSseServerTransportProvider> afterSalesMcpServletRegistration(
            HttpServletSseServerTransportProvider transportProvider) {
        ServletRegistrationBean<HttpServletSseServerTransportProvider> registration =
                new ServletRegistrationBean<>(transportProvider, MCP_SSE_ENDPOINT, MCP_MESSAGE_ENDPOINT);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "close")
    public McpSyncServer afterSalesMcpSyncServer(
            HttpServletSseServerTransportProvider transportProvider,
            SyncMcpToolProvider toolProvider) {
        return McpServer.sync(transportProvider)
                .serverInfo("ai-agent-station-after-sales", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .tools(toolProvider.getToolSpecifications())
                .build();
    }
}
