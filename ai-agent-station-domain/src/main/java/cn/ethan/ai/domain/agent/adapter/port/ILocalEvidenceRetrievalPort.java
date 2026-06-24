package cn.ethan.ai.domain.agent.adapter.port;

import cn.ethan.ai.domain.agent.model.valobj.EvidenceRetrievalRequestVO;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 项目知识库检索端口。外部 MCP evidence 不经过该端口。
 */
public interface ILocalEvidenceRetrievalPort {

    List<Document> retrieve(EvidenceRetrievalRequestVO request);
}
