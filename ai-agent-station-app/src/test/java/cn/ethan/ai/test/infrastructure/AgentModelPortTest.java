package cn.ethan.ai.test.infrastructure;

import cn.ethan.ai.infrastructure.adapter.port.AgentModelPort;
import cn.ethan.ai.infrastructure.adapter.port.GuardedToolCallback;
import cn.ethan.ai.domain.agent.model.valobj.ToolInvocationRecordVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
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

        Assertions.assertEquals(List.of("doc-1"), metadata.get("qa_retrieved_documents"));
        Assertions.assertEquals(List.of("query-1"), metadata.get("qa_retrieval_queries"));
        Assertions.assertEquals("[证据1] doc-1", metadata.get("question_answer_context"));
    }

    @Test
    public void guardedToolCallbackShouldReturnUnifiedArgumentError() {
        GuardedToolCallback callback = new GuardedToolCallback(
                new StubToolCallback("web_search_exa", new IllegalArgumentException("keyword required")),
                Set.of("web_search_exa")
        );

        String result = callback.call("{}");

        Assertions.assertTrue(result.contains("\"success\":false"));
        Assertions.assertTrue(result.contains("TOOL_ARGUMENT_INVALID"));
        Assertions.assertTrue(result.contains("keyword required"));
    }

    @Test
    public void guardedToolCallbackShouldRejectToolOutsideAuthorizedSet() {
        GuardedToolCallback callback = new GuardedToolCallback(
                new StubToolCallback("web_search_exa", null),
                Set.of("get_library_docs")
        );

        String result = callback.call("{}");

        Assertions.assertTrue(result.contains("TOOL_NOT_AUTHORIZED"));
    }

    @Test
    public void guardedToolCallbackShouldRejectDangerousToolEvenIfInjected() {
        GuardedToolCallback callback = new GuardedToolCallback(
                new StubToolCallback("execute_shell", null),
                Set.of("execute_shell")
        );

        String result = callback.call("{\"command\":\"del\"}");

        Assertions.assertTrue(result.contains("TOOL_FORBIDDEN"));
        Assertions.assertTrue(result.contains("execute_shell"));
    }

    @Test
    public void onlyExternalEvidenceStageShouldRequireARealToolCall() throws Exception {
        Method method = AgentModelPort.class.getDeclaredMethod("requiresToolCall", String.class);
        method.setAccessible(true);

        Assertions.assertEquals(true, method.invoke(null, "harness_external_evidence"));
        Assertions.assertEquals(false, method.invoke(null, "harness_action_decision"));
        Assertions.assertEquals(false, method.invoke(null, "harness_grounded_answer"));
    }

    @Test
    public void evidenceRouteShouldAcceptDiscoveredReadOnlyToolAfterServerRename() throws Exception {
        Method method = AgentModelPort.class.getDeclaredMethod(
                "isToolAuthorized", String.class, Set.class, boolean.class);
        method.setAccessible(true);

        Assertions.assertEquals(true,
                method.invoke(null, "query-docs", Set.of("get-library-docs"), true));
        Assertions.assertEquals(false,
                method.invoke(null, "create_document", Set.of("get-library-docs"), true));
        Assertions.assertEquals(false,
                method.invoke(null, "query-docs", Set.of("get-library-docs"), false));
    }

    @Test
    public void externalEvidenceShouldKeepCompletedToolRecordsWhenModelPostProcessingFails() throws Exception {
        Method method = AgentModelPort.class.getDeclaredMethod(
                "fallbackFromToolInvocations", String.class, List.class);
        method.setAccessible(true);
        ToolInvocationRecordVO record = ToolInvocationRecordVO.builder()
                .toolName("query-docs")
                .success(true)
                .output("evidence")
                .build();

        Object fallback = method.invoke(null, "harness_external_evidence", List.of(record));
        Object ordinary = method.invoke(null, "harness_grounded_answer", List.of(record));

        Assertions.assertNotNull(fallback);
        Assertions.assertNull(ordinary);
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
