package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.adapter.port.IRagRetrievalPort;
import cn.ethan.ai.domain.agent.model.valobj.AiClientAdvisorVO;
import cn.ethan.ai.domain.agent.service.armory.factory.element.RagAnswerAdvisor;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RagAnswerAdvisorTest {

    @Test
    public void beforeShouldExposeRetrievalQueriesAndDocuments() {
        RecordingRetrievalPort retrievalPort = new RecordingRetrievalPort();
        AiClientAdvisorVO.RagAnswer ragAnswer = AiClientAdvisorVO.RagAnswer.builder()
                .topK(2)
                .routeTopK(3)
                .maxRewriteQueries(3)
                .queryRewriteEnabled(true)
                .deduplicateEnabled(true)
                .build();
        RagAnswerAdvisor advisor = new RagAnswerAdvisor(retrievalPort, SearchRequest.builder().topK(2).build(), ragAnswer);

        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(Prompt.builder().messages(new UserMessage("请帮我总结 Spring AI MCP 工具接入流程")).build())
                .context(Map.of("traceId", "test-run"))
                .build();

        ChatClientRequest advisedRequest = advisor.before(request, new AdvisorChain() {
        });

        Assert.assertEquals(3, retrievalPort.queries.size());
        Assert.assertEquals(3, advisedRequest.context().get("qa_retrieval_queries") instanceof List<?> list ? list.size() : 0);
        Assert.assertTrue(advisedRequest.context().get("qa_retrieved_documents") instanceof List<?>);
        Assert.assertTrue(advisedRequest.context().containsKey("question_answer_context"));
        Assert.assertTrue(advisedRequest.context().get("question_answer_context").toString().contains("[证据1]"));
        Assert.assertTrue(advisedRequest.prompt().getUserMessage().getText().contains("[证据1]"));
        Assert.assertTrue(advisedRequest.prompt().getUserMessage().getText().contains("请优先基于知识库证据"));
    }

    private static class RecordingRetrievalPort implements IRagRetrievalPort {

        private final List<String> queries = new ArrayList<>();

        @Override
        public List<Document> retrieve(SearchRequest request, Map<String, Object> context) {
            this.queries.add(request.getQuery());
            return List.of(Document.builder()
                    .id("doc-" + this.queries.size())
                    .text("Spring AI MCP 工具接入流程说明 " + this.queries.size())
                    .metadata(Map.of("doc_id", "spring-ai", "chunk_id", String.valueOf(this.queries.size())))
                    .score(1.0D - this.queries.size() * 0.1D)
                    .build());
        }
    }

}
