package cn.ethan.ai.domain.agent.service.armory.factory.element;

import cn.ethan.ai.domain.agent.model.valobj.AiClientAdvisorVO;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RagAnswerAdvisor implements BaseAdvisor {

    private final VectorStore vectorStore;
    private final SearchRequest searchRequest;
    private final AiClientAdvisorVO.RagAnswer ragAnswer;
    private final RagRetrievalSupport ragRetrievalSupport;
    private final String userTextAdvise;

    public RagAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest) {
        this(vectorStore, searchRequest, new AiClientAdvisorVO.RagAnswer());
    }

    public RagAnswerAdvisor(VectorStore vectorStore, SearchRequest searchRequest, AiClientAdvisorVO.RagAnswer ragAnswer) {
        this.vectorStore = vectorStore;
        this.searchRequest = searchRequest;
        this.ragAnswer = ragAnswer == null ? new AiClientAdvisorVO.RagAnswer() : ragAnswer;
        this.ragRetrievalSupport = new RagRetrievalSupport();
        this.userTextAdvise = """

                以下是可用知识库证据，已用分隔线包裹：

                ---------------------
                {question_answer_context}
                ---------------------

                请优先基于知识库证据和历史上下文回答用户问题；如果证据不足，请明确说明无法从知识库确认。
                """;
    }

    @Override
    public @NonNull ChatClientRequest before(@NonNull ChatClientRequest chatClientRequest, @NonNull AdvisorChain advisorChain) {
        HashMap<String, Object> context = new HashMap<>(chatClientRequest.context());

        String userText = chatClientRequest.prompt().getUserMessage().getText();

        List<String> retrievalQueries = this.ragAnswer.isQueryRewriteEnabled()
                ? this.ragRetrievalSupport.rewriteQueries(userText, this.ragAnswer.getMaxRewriteQueries())
                : List.of(userText);

        List<Document> retrievedDocuments = new ArrayList<>();
        for (String retrievalQuery : retrievalQueries) {
            SearchRequest searchRequestToUse = SearchRequest.from(this.searchRequest)
                    .query(retrievalQuery)
                    .topK(resolveRouteTopK())
                    .filterExpression(this.doGetFilterExpression(context))
                    .build();
            retrievedDocuments.addAll(this.vectorStore.similaritySearch(searchRequestToUse));
        }

        List<Document> documents = this.ragAnswer.isDeduplicateEnabled()
                ? this.ragRetrievalSupport.mergeAndDeduplicate(retrievedDocuments, this.ragAnswer.getTopK(), this.ragAnswer.getContentFingerprintLength())
                : retrievedDocuments.stream().limit(this.ragAnswer.getTopK()).collect(Collectors.toList());

        context.put("qa_retrieved_documents", documents);
        context.put("qa_retrieval_queries", retrievalQueries);

        String documentContext = this.ragRetrievalSupport.formatEvidenceContext(documents);
        Map<String, Object> advisedUserParams = new HashMap<>(context);
        advisedUserParams.put("question_answer_context", documentContext);
        advisedUserParams.put("qa_retrieval_queries", retrievalQueries);

        String advisedUserText = userText + System.lineSeparator() + this.userTextAdvise
                .replace("{question_answer_context}", documentContext);

        return ChatClientRequest.builder()
                .prompt(Prompt.builder().messages(new UserMessage(advisedUserText)).build())
                .context(advisedUserParams)
                .build();
    }

    @Override
    public @NonNull ChatClientResponse after(ChatClientResponse chatClientResponse, @NonNull AdvisorChain advisorChain) {
        assert chatClientResponse.chatResponse() != null;
        ChatResponse.Builder chatResponseBuilder = ChatResponse.builder().from(chatClientResponse.chatResponse());
        chatResponseBuilder.metadata("qa_retrieved_documents", chatClientResponse.context().get("qa_retrieved_documents"));
        chatResponseBuilder.metadata("qa_retrieval_queries", chatClientResponse.context().get("qa_retrieval_queries"));
        ChatResponse chatResponse = chatResponseBuilder.build();

        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(chatClientResponse.context())
                .build();
    }

    @Override
    public @NonNull ChatClientResponse adviseCall(@NonNull ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(this.before(chatClientRequest, callAdvisorChain));
        return this.after(chatClientResponse, callAdvisorChain);
    }

    @Override
    public @NonNull Flux<ChatClientResponse> adviseStream(@NonNull ChatClientRequest chatClientRequest, @NonNull StreamAdvisorChain streamAdvisorChain) {
        return BaseAdvisor.super.adviseStream(chatClientRequest, streamAdvisorChain);
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public @NonNull String getName() {
        return this.getClass().getSimpleName();
    }

    protected Filter.Expression doGetFilterExpression(Map<String, Object> context) {
        return context.containsKey("qa_filter_expression") && StringUtils.hasText(context.get("qa_filter_expression").toString()) ? (new FilterExpressionTextParser()).parse(context.get("qa_filter_expression").toString()) : this.searchRequest.getFilterExpression();
    }

    private int resolveRouteTopK() {
        return this.ragAnswer.getRouteTopK() <= 0 ? this.ragAnswer.getTopK() : this.ragAnswer.getRouteTopK();
    }

}
