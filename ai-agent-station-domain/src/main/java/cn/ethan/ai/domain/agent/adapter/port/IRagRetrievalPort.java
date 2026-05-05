package cn.ethan.ai.domain.agent.adapter.port;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.List;
import java.util.Map;

/**
 * RAG 检索端口，隔离具体召回实现（向量库、BM25 等）。
 */
public interface IRagRetrievalPort {

    /**
     * 执行一次检索请求，返回已融合排序后的文档列表。
     *
     * @param searchRequest 检索请求
     * @param context       对话上下文
     * @return 检索结果
     */
    List<Document> retrieve(SearchRequest searchRequest, Map<String, Object> context);

}
