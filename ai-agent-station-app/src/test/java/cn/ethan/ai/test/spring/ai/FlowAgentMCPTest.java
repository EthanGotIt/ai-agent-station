package cn.ethan.ai.test.spring.ai;

import com.alibaba.fastjson.JSON;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class FlowAgentMCPTest {

    @Test
    public void test() {
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/")
                        .apiKey("sk-0ec8457a16644d5ca60a3468b20463e2")
                        .completionsPath("v1/chat/completions")
                        .embeddingsPath("v1/embeddings")
                        .build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("qwen3.5-plus")
                        .toolCallbacks(SyncMcpToolCallbackProvider.builder()
                                .mcpClients(stdioMcpClient_Grafana())
                                .build()
                                .getToolCallbacks())
                        .build())
                .build();

        ChatResponse call = chatModel.call(Prompt.builder().messages(new UserMessage("你有哪些工具可以使用?")).build());
        log.info("测试结果:{}", JSON.toJSONString(call.getResult()));
    }

    public McpSyncClient stdioMcpClientElasticsearch() {
        Map<String, String> env = new HashMap<>();
        env.put("ES_HOST", "http://127.0.0.1:9200");
        env.put("ES_API_KEY", "none");

        var stdioParams = ServerParameters.builder("npx.cmd")
                .args("-y", "@awesome-ai/elasticsearch-mcp")
                .env(env)
                .build();

        var mcpClient = McpClient.sync(new StdioClientTransport(stdioParams, McpJsonMapper.getDefault()))
                .requestTimeout(Duration.ofSeconds(100)).build();

        var init = mcpClient.initialize();

        System.out.println("Stdio MCP Initialized: " + init);

        return mcpClient;

    }

    public McpSyncClient stdioMcpClient_Grafana() {
        Map<String, String> env = new HashMap<>();
        env.put("GRAFANA_URL", "http://127.0.0.1:9200");
        env.put("GRAFANA_API_KEY", "glsa_IObTIVBoma3KsCmse8V9iNRDeZrV1L99_cab3b0b0");

        var stdioParams = ServerParameters.builder("docker")
                .args("run",
                        "--rm",
                        "-i",
                        "-e",
                        "GRAFANA_URL",
                        "-e",
                        "GRAFANA_API_KEY",
                        "mcp/grafana",
                        "-t",
                        "stdio")
                .env(env)
                .build();

        var mcpClient = McpClient.sync(new StdioClientTransport(stdioParams, McpJsonMapper.getDefault()))
                .requestTimeout(Duration.ofSeconds(100)).build();

        var init = mcpClient.initialize();
        log.info("Stdio MCP Initialized: {}", init);

        return mcpClient;
    }
}
