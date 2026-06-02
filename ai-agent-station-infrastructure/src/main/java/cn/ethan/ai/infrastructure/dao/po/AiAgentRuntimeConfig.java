package cn.ethan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAgentRuntimeConfig {

    private Long id;

    private String agentId;

    private String clientId;

    private Integer maxModelCalls;

    private Integer maxToolCalls;

    private Integer status;

}
