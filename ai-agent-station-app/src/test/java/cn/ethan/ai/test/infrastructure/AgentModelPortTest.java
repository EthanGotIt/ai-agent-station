package cn.ethan.ai.test.infrastructure;

import cn.ethan.ai.infrastructure.adapter.port.AgentModelPort;
import cn.ethan.ai.infrastructure.adapter.port.GuardedToolCallback;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AgentModelPortTest {

    @Test
    @SuppressWarnings("unchecked")
    public void extractMetadataShouldMergeRagContext() throws Exception {
        AgentModelPort agentModelPort = new AgentModelPort();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        ChatClientResponse chatClientResponse = new ChatClientResponse(chatResponse, Map.of(
                "qa_retrieved_documents", List.of("doc-1"),
                "qa_retrieval_queries", List.of("query-1"),
                "question_answer_context", "[证据1] doc-1"
        ));

        Method method = AgentModelPort.class.getDeclaredMethod("extractMetadata", ChatClientResponse.class, ChatResponse.class);
        method.setAccessible(true);
        Map<String, Object> metadata = (Map<String, Object>) method.invoke(agentModelPort, chatClientResponse, chatResponse);

        Assert.assertEquals(List.of("doc-1"), metadata.get("qa_retrieved_documents"));
        Assert.assertEquals(List.of("query-1"), metadata.get("qa_retrieval_queries"));
        Assert.assertEquals("[证据1] doc-1", metadata.get("question_answer_context"));
    }

    @Test
    public void guardedToolCallbackShouldReturnUnifiedArgumentError() {
        GuardedToolCallback callback = new GuardedToolCallback(
                new StubToolCallback("web_search_exa", new IllegalArgumentException("keyword required")),
                Set.of("web_search_exa")
        );

        String result = callback.call("{}");

        Assert.assertTrue(result.contains("\"success\":false"));
        Assert.assertTrue(result.contains("TOOL_ARGUMENT_INVALID"));
        Assert.assertTrue(result.contains("keyword required"));
    }

    @Test
    public void guardedToolCallbackShouldRejectToolOutsideAuthorizedSet() {
        GuardedToolCallback callback = new GuardedToolCallback(
                new StubToolCallback("web_search_exa", null),
                Set.of("get_library_docs")
        );

        String result = callback.call("{}");

        Assert.assertTrue(result.contains("TOOL_NOT_AUTHORIZED"));
    }

    @Test
    public void guardedToolCallbackShouldRejectDangerousToolEvenIfInjected() {
        GuardedToolCallback callback = new GuardedToolCallback(
                new StubToolCallback("execute_shell", null),
                Set.of("execute_shell")
        );

        String result = callback.call("{\"command\":\"del\"}");

        Assert.assertTrue(result.contains("TOOL_FORBIDDEN"));
        Assert.assertTrue(result.contains("execute_shell"));
    }

    private static class StubToolCallback implements ToolCallback {

        private final ToolDefinition toolDefinition;

        private final RuntimeException exception;

        private StubToolCallback(String toolName, RuntimeException exception) {
            this.toolDefinition = ToolDefinition.builder()
                    .name(toolName)
                    .description("stub")
                    .inputSchema("{}")
                    .build();
            this.exception = exception;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public String call(String toolInput) {
            if (exception != null) {
                throw exception;
            }
            return "ok";
        }
    }
}
