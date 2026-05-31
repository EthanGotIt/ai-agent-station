package cn.ethan.ai.domain.agent.service.armory.factory.element;

import cn.ethan.ai.domain.agent.adapter.port.IRagRetrievalPort;
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

    private static final int MAX_RETRIEVAL_QUERY_CHARS = 240;

    private static final List<String> RETRIEVAL_HINTS = List.of(
            "知识库", "已导入", "基于", "证据", "文档", "资料", "检索", "召回",
            "rag", "mcp", "spring ai", "ai agent station", "接入", "配置", "流程", "架构", "机制"
    );

    private static final List<String> NON_RETRIEVAL_HINTS = List.of(
            "润色", "改写", "翻译", "生成文案", "写一段", "写一篇"
    );

    private final IRagRetrievalPort ragRetrievalPort;
    private final SearchRequest searchRequest;
    private final AiClientAdvisorVO.RagAnswer ragAnswer;
    private final RagRetrievalSupport ragRetrievalSupport;
    private final String userTextAdvise;

    public RagAnswerAdvisor(IRagRetrievalPort ragRetrievalPort, SearchRequest searchRequest) {
        this(ragRetrievalPort, searchRequest, new AiClientAdvisorVO.RagAnswer());
    }

    public RagAnswerAdvisor(IRagRetrievalPort ragRetrievalPort, SearchRequest searchRequest, AiClientAdvisorVO.RagAnswer ragAnswer) {
        this.ragRetrievalPort = ragRetrievalPort;
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
        boolean ragPlanStep = isRagPlanStep(userText);
        String retrievalBaseQuery = resolveRetrievalBaseQuery(userText);
        if (!StringUtils.hasText(retrievalBaseQuery)) {
            return buildPassthroughRequest(chatClientRequest, context, "当前提示词属于内部编排上下文，跳过知识检索。");
        }
        if (!ragPlanStep && !shouldUseRetrieval(retrievalBaseQuery)) {
            return buildPassthroughRequest(chatClientRequest, context, "非 RAG 请求，跳过知识检索。");
        }

        List<String> retrievalQueries = this.ragAnswer.isQueryRewriteEnabled()
                ? this.ragRetrievalSupport.rewriteQueries(retrievalBaseQuery, this.ragAnswer.getMaxRewriteQueries())
                : List.of(retrievalBaseQuery);
        retrievalQueries = retrievalQueries.stream()
                .map(this::normalizeRetrievalQuery)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (retrievalQueries.isEmpty()) {
            return buildPassthroughRequest(chatClientRequest, context, "未生成有效检索查询，跳过知识检索。");
        }

        List<List<Document>> routeDocuments = new ArrayList<>();
        for (String retrievalQuery : retrievalQueries) {
            SearchRequest searchRequestToUse = SearchRequest.from(this.searchRequest)
                    .query(retrievalQuery)
                    .topK(resolveRouteTopK())
                    .filterExpression(this.doGetFilterExpression(context))
                    .build();
            routeDocuments.add(this.ragRetrievalPort.retrieve(searchRequestToUse, context));
        }

        List<Document> documents;
        if (this.ragAnswer.isDeduplicateEnabled()) {
            List<Document> fusedDocuments = this.ragRetrievalSupport.rrfFuse(routeDocuments, this.ragAnswer.getTopK() * 3, 60);
            documents = this.ragRetrievalSupport.deduplicateByParent(fusedDocuments, this.ragAnswer.getTopK());
            if (documents.isEmpty()) {
                documents = this.ragRetrievalSupport.mergeAndDeduplicate(
                        routeDocuments.stream().flatMap(List::stream).collect(Collectors.toList()),
                        this.ragAnswer.getTopK(),
                        this.ragAnswer.getContentFingerprintLength()
                );
            }
        } else {
            documents = routeDocuments.stream()
                    .flatMap(List::stream)
                    .limit(this.ragAnswer.getTopK())
                    .collect(Collectors.toList());
        }

        context.put("qa_retrieved_documents", documents);
        context.put("qa_retrieval_queries", retrievalQueries);
        context.put("qa_retrieval_pipeline", List.of("query_rewrite", "hybrid_recall", "rrf_fusion", "small_to_big", "deduplicate"));
        context.put("qa_retrieval_no_evidence", documents.isEmpty());
        context.put("question_answer_context", this.ragRetrievalSupport.formatEvidenceContext(documents));

        Map<String, Object> advisedUserParams = new HashMap<>(context);
        advisedUserParams.put("qa_retrieval_queries", retrievalQueries);
        advisedUserParams.put("question_answer_context", context.get("question_answer_context"));

        if (documents.isEmpty()) {
            return ChatClientRequest.builder()
                    .prompt(chatClientRequest.prompt())
                    .context(advisedUserParams)
                    .build();
        }

        String advisedUserText = userText + System.lineSeparator() + this.userTextAdvise
                .replace("{question_answer_context}", context.get("question_answer_context").toString());

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
        chatResponseBuilder.metadata("qa_retrieval_pipeline", chatClientResponse.context().get("qa_retrieval_pipeline"));
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

    private String resolveRetrievalBaseQuery(String userText) {
        if (!StringUtils.hasText(userText)) {
            return "";
        }
        String normalized = userText.trim();
        if (!looksLikeFlowInternalPrompt(normalized)) {
            return normalizeRetrievalQuery(normalized);
        }

        String originalRequest = extractSection(normalized, "用户原始请求：", "计划目标：");
        if (StringUtils.hasText(originalRequest)) {
            return normalizeRetrievalQuery(originalRequest);
        }
        return "";
    }

    private boolean looksLikeFlowInternalPrompt(String text) {
        return containsAny(text, "当前步骤：", "已完成步骤输出：", "执行计划：", "质量监督结果：", "请返回：");
    }

    private boolean isRagPlanStep(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return text.contains("\"type\":\"RAG\"") || text.contains("\"type\": \"RAG\"");
    }

    private boolean shouldUseRetrieval(String retrievalBaseQuery) {
        if (!StringUtils.hasText(retrievalBaseQuery)) {
            return false;
        }
        String normalized = retrievalBaseQuery.trim().toLowerCase();
        if (containsAny(normalized, NON_RETRIEVAL_HINTS.toArray(String[]::new))
                && !containsAny(normalized, RETRIEVAL_HINTS.toArray(String[]::new))) {
            return false;
        }
        return containsAny(normalized, RETRIEVAL_HINTS.toArray(String[]::new));
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String extractSection(String text, String startMarker, String endMarker) {
        int startIndex = text.indexOf(startMarker);
        if (startIndex < 0) {
            return "";
        }
        int contentStart = startIndex + startMarker.length();
        int endIndex = StringUtils.hasText(endMarker) ? text.indexOf(endMarker, contentStart) : -1;
        String section = endIndex > contentStart ? text.substring(contentStart, endIndex) : text.substring(contentStart);
        return section == null ? "" : section.trim();
    }

    private String normalizeRetrievalQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return "";
        }
        String normalized = query.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_RETRIEVAL_QUERY_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_RETRIEVAL_QUERY_CHARS).trim();
    }

    private ChatClientRequest buildPassthroughRequest(ChatClientRequest originalRequest,
                                                      Map<String, Object> originalContext,
                                                      String reason) {
        Map<String, Object> advisedUserParams = new HashMap<>(originalContext);
        advisedUserParams.put("qa_retrieved_documents", List.of());
        advisedUserParams.put("qa_retrieval_queries", List.of());
        advisedUserParams.put("qa_retrieval_pipeline", List.of());
        advisedUserParams.put("qa_retrieval_no_evidence", true);
        advisedUserParams.put("question_answer_context", "");
        advisedUserParams.put("qa_retrieval_skipped_reason", reason);
        return ChatClientRequest.builder()
                .prompt(originalRequest.prompt())
                .context(advisedUserParams)
                .build();
    }

}
