package cn.ethan.ai.test.support;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;

public final class McpTestSupport {

    private McpTestSupport() {
    }

    public static StdioClientTransport stdioTransport(ServerParameters serverParameters) {
        return new StdioClientTransport(serverParameters, new JacksonMcpJsonMapperSupplier().get());
    }
}
