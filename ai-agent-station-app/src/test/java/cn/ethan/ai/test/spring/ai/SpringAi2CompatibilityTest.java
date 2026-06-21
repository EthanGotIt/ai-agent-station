package cn.ethan.ai.test.spring.ai;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SpringAi2CompatibilityTest {

    @Test
    public void mutatedOptionsShouldRetainConnectionConfiguration() {
        OpenAiChatOptions connectionOptions = OpenAiChatOptions.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .apiKey("test-key")
                .build();

        OpenAiChatOptions modelOptions = connectionOptions.mutate()
                .model("qwen3.7-max")
                .build();

        Assert.assertEquals(connectionOptions.getBaseUrl(), modelOptions.getBaseUrl());
        Assert.assertEquals(connectionOptions.getApiKey(), modelOptions.getApiKey());
        Assert.assertEquals("qwen3.7-max", modelOptions.getModel());
    }

    @Test
    public void requestScopedToolCallbackShouldCompleteToolCallingLoop() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        ChatModel chatModel = new ScriptedToolCallingChatModel(modelCalls);
        ToolCallback toolCallback = new CountingToolCallback(toolCalls);

        String result = ChatClient.builder(chatModel)
                .build()
                .prompt("使用 echo 工具")
                .tools(toolCallback)
                .call()
                .content();

        Assert.assertEquals("tool completed", result);
        Assert.assertEquals(2, modelCalls.get());
        Assert.assertEquals(1, toolCalls.get());
    }

    private static class ScriptedToolCallingChatModel implements ChatModel {

        private final AtomicInteger modelCalls;

        private ScriptedToolCallingChatModel(AtomicInteger modelCalls) {
            this.modelCalls = modelCalls;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (modelCalls.incrementAndGet() == 1) {
                AssistantMessage toolRequest = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "echo_tool", "{\"text\":\"hello\"}"
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(toolRequest)));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("tool completed"))));
        }

        @Override
        public OpenAiChatOptions getOptions() {
            return OpenAiChatOptions.builder().model("stub-model").build();
        }
    }

    private static class CountingToolCallback implements ToolCallback {

        private final AtomicInteger calls;

        private CountingToolCallback(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("echo_tool")
                    .description("Echoes input for compatibility testing")
                    .inputSchema("""
                            {"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}
                            """)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            calls.incrementAndGet();
            return "echo:hello";
        }
    }
}
