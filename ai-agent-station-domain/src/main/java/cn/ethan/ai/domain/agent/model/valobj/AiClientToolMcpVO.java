package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * MCP客户端配置，值对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientToolMcpVO {

    /**
     * MCP ID
     */
    private String mcpId;

    /**
     * MCP名称
     */
    private String mcpName;

    /**
     * 传输类型(stdio/sse/streamable_http)
     */
    private String transportType;

    /**
     * 传输配置(stdio/sse/streamable_http)
     */
    private String transportConfig;

    /**
     * 请求超时时间(分钟)
     */
    private Integer requestTimeout;

    /**
     * 传输配置 - stdio
     */
    private TransportConfigStdio transportConfigStdio;

    /**
     * 传输配置 - sse
     */
    private TransportConfigSse transportConfigSse;

    /**
     * 传输配置 - streamable_http
     */
    private TransportConfigStreamableHttp transportConfigStreamableHttp;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TransportConfigStdio {

        private String command;

        private List<String> args;

        private Map<String, String> env;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TransportConfigSse {

        private String baseUri;

        private String sseEndpoint;
        /**
         * 自定义请求头，用于认证等场景
         * 例如: {"Authorization": "Bearer token123"}
         */
        private Map<String, String> headers;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TransportConfigStreamableHttp {
        /**
         * 服务器基础URL，例如: http://localhost:8080
         */
        private String baseUri;
        /**
         * MCP 接口端点，例如: /mcp
         */
        private String endpoint;
        /**
         * 自定义请求头，用于认证等场景
         * 例如: {"Authorization": "Bearer token123"}
         */
        private Map<String, String> headers;
    }

}
