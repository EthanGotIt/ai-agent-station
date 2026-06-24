package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型给出的证据评估。最终是否允许回答仍由 Evidence Policy 决定。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceAssessmentVO {

    private boolean sufficient;

    private double coverage;

    private double confidence;

    @Builder.Default
    private List<String> gaps = new ArrayList<>();

    private String reason;
}
