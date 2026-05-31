package cn.ethan.ai.test.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;

public final class McpTestSupport {

    private static final ObjectMapper MCP_OBJECT_MAPPER = new ObjectMapper();

    private McpTestSupport() {
    }

    public static StdioClientTransport stdioTransport(ServerParameters serverParameters) {
        return new StdioClientTransport(serverParameters, new JacksonMcpJsonMapper(MCP_OBJECT_MAPPER));
    }
}
