package cn.ethan.ai.domain.agent.model.valobj;

import cn.ethan.ai.domain.agent.model.valobj.enums.EvidenceSourceTypeEnumVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次高层来源检索结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceRetrievalResultVO {

    private EvidenceSourceTypeEnumVO sourceType;

    @Builder.Default
    private List<Document> documents = new ArrayList<>();

    @Builder.Default
    private List<String> queries = new ArrayList<>();

    private String channel;

    private String reason;

    private long costMillis;
}
