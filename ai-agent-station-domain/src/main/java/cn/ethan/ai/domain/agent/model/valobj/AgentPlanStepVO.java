package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 执行计划步骤值对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentPlanStepVO {

    private String stepId;

    private String name;

    private String type;

    private String toolName;

    @Builder.Default
    private Map<String, Object> input = new LinkedHashMap<>();

    @Builder.Default
    private List<String> dependsOn = new ArrayList<>();

    private String successCriteria;

}
