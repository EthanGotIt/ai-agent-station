package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单 Agent GraphRuntime 配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAgentRuntimeConfigVO {

    private String agentId;

    private String clientId;

    private Integer maxModelCalls;

    private Integer maxToolCalls;

}
