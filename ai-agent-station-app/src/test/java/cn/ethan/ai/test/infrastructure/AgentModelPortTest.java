package cn.ethan.ai.test.infrastructure;

import cn.ethan.ai.infrastructure.adapter.port.AgentModelPort;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

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
}
