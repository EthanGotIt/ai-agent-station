package cn.ethan.ai.domain.agent.adapter.port;

import cn.ethan.ai.domain.agent.model.entity.AgentRunTraceEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.entity.AgentModelCallResultEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextWindowGuardVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;

import java.util.Map;

/**
 * Agent 模型调用端口
 */
public interface IAgentModelPort {

    boolean hasAvailableModelClient(Map<String, AiAgentClientFlowConfigVO> flowConfigMap, AiClientTypeEnumVO... clientTypes);

    String callModel(Map<String, AiAgentClientFlowConfigVO> flowConfigMap,
                     ExecuteCommandEntity command,
                     ContextWindowGuardVO contextWindowGuard,
                     AgentRunTraceEntity trace,
                     String prompt,
                     String eventType,
                     String stepId,
                     Integer step,
                     ToolRoutingDecisionVO toolRoutingDecision,
                     AiClientTypeEnumVO... clientTypes);

    default AgentModelCallResultEntity callModelResult(Map<String, AiAgentClientFlowConfigVO> flowConfigMap,
                                                       ExecuteCommandEntity command,
                                                       ContextWindowGuardVO contextWindowGuard,
                                                       AgentRunTraceEntity trace,
                                                       String prompt,
                                                       String eventType,
                                                       String stepId,
                                                       Integer step,
                                                       ToolRoutingDecisionVO toolRoutingDecision,
                                                       AiClientTypeEnumVO... clientTypes) {
        return AgentModelCallResultEntity.builder()
                .content(callModel(flowConfigMap, command, contextWindowGuard, trace, prompt, eventType, stepId, step, toolRoutingDecision, clientTypes))
                .build();
    }
}
