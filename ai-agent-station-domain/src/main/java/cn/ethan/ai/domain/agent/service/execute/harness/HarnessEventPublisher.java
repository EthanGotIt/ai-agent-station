package cn.ethan.ai.domain.agent.service.execute.harness;

import cn.ethan.ai.domain.agent.adapter.port.IAgentStreamPort;
import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.model.valobj.HarnessObservationVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一发布 Harness 流式事件，业务执行服务不感知 payload 拆分细节。
 */
@Slf4j
@Service
public class HarnessEventPublisher {

    public void observation(ExecuteCommandEntity command,
                            AgentExecutionContextVO context,
                            AgentRunAggregate run,
                            HarnessObservationVO observation) {
        HarnessObservationVO compact = observation;
        Object ragEvidence = observation.getPayload() == null ? null
                : observation.getPayload().get(HarnessActionExecutor.RAG_EVIDENCE_SUB_TYPE);
        if (ragEvidence != null) {
            send(context, AgentExecuteResultEntity.createExecutionSubResult(
                    context.nextStreamStepCursor(), HarnessActionExecutor.RAG_EVIDENCE_SUB_TYPE,
                    "受控 Agentic RAG 证据轨迹已更新。", ragEvidence,
                    command.getSessionId(), run.runId()));
            Map<String, Object> payload = new LinkedHashMap<>(observation.getPayload());
            payload.remove(HarnessActionExecutor.RAG_EVIDENCE_SUB_TYPE);
            compact = HarnessObservationVO.builder()
                    .actionId(observation.getActionId())
                    .actionType(observation.getActionType())
                    .success(observation.isSuccess())
                    .terminal(observation.isTerminal())
                    .message(observation.getMessage())
                    .payload(payload)
                    .build();
        }
        send(context, AgentExecuteResultEntity.createExecutionSubResult(
                context.nextStreamStepCursor(), "harness_observation", compact.getMessage(), compact,
                command.getSessionId(), run.runId()));
    }

    public void send(AgentExecutionContextVO context, AgentExecuteResultEntity result) {
        try {
            IAgentStreamPort streamPort = context.getStreamPort();
            if (streamPort != null) {
                streamPort.send(result);
            }
        } catch (Exception e) {
            log.warn("发送 Harness 流式结果失败：{}", e.getMessage(), e);
        }
    }
}
