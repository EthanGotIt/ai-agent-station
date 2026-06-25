package cn.ethan.ai.domain.agent.service.execute.springai.advisor;

import cn.ethan.ai.domain.agent.adapter.port.ILocalEvidenceRetrievalPort;
import cn.ethan.ai.domain.agent.model.valobj.EvidenceRetrievalRequestVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.EvidenceSourceTypeEnumVO;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.document.Document;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于 Advisor 的项目知识检索，将隐式 RAG 替换为显式 evidence 上下文。
 */
public class EvidenceRetrievalAdvisor implements BaseAdvisor {

    public static final String RETRIEVED_DOCUMENTS = "qa_retrieved_documents";

    private static final int DEFAULT_TOP_K = 4;

    private final ILocalEvidenceRetrievalPort retrievalPort;

    private final Set<String> ragIds;

    public EvidenceRetrievalAdvisor(ILocalEvidenceRetrievalPort retrievalPort, Set<String> ragIds) {
        this.retrievalPort = retrievalPort;
        this.ragIds = ragIds == null ? Set.of() : ragIds.stream()
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public @NonNull ChatClientRequest before(@NonNull ChatClientRequest request, @NonNull AdvisorChain advisorChain) {
        if (retrievalPort == null || ragIds.isEmpty()) {
            return request;
        }
        String query = request.prompt().getContents();
        List<Document> documents = retrievalPort.retrieve(EvidenceRetrievalRequestVO.builder()
                .query(query)
                .sourceType(EvidenceSourceTypeEnumVO.PROJECT_KNOWLEDGE)
                .ragIds(ragIds)
                .topK(DEFAULT_TOP_K)
                .retrievalRound(1)
                .build());
        if (documents == null || documents.isEmpty()) {
            return request;
        }
        String evidenceContext = documents.stream()
                .limit(DEFAULT_TOP_K)
                .map(this::renderDocument)
                .collect(Collectors.joining("\n\n"));
        String augmentedPrompt = "\n\n请优先基于以下项目知识 evidence 回答，并用 [E1]、[E2] 形式引用：\n"
                + evidenceContext;
        return request.mutate()
                .prompt(request.prompt().augmentUserMessage(augmentedPrompt))
                .context(RETRIEVED_DOCUMENTS, documents)
                .build();
    }

    @Override
    public @NonNull ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain advisorChain) {
        return response;
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE + 200;
    }

    private String renderDocument(Document document) {
        Object source = document.getMetadata().get("title");
        Object uri = document.getMetadata().get("uri");
        return "- [%s] %s\n%s".formatted(
                StringUtils.defaultIfBlank(source == null ? "" : source.toString(), "项目知识"),
                StringUtils.defaultString(uri == null ? "" : uri.toString()),
                StringUtils.abbreviate(StringUtils.defaultString(document.getText()), 1200));
    }
}
