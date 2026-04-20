package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 结构化执行计划值对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentPlanVO {

    private String goal;

    @Builder.Default
    private List<AgentPlanStepVO> steps = new ArrayList<>();

}
