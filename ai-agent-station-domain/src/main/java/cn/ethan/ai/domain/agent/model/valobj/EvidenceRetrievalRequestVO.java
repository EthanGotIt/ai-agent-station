package cn.ethan.ai.domain.agent.model.valobj;

import cn.ethan.ai.domain.agent.model.valobj.enums.EvidenceSourceTypeEnumVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 单次证据检索请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceRetrievalRequestVO {

    private String query;

    private EvidenceSourceTypeEnumVO sourceType;

    @Builder.Default
    private Set<String> ragIds = new LinkedHashSet<>();

    @Builder.Default
    private int topK = 4;

    private int retrievalRound;
}
