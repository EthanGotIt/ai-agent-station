package cn.ethan.ai.domain.agent.model.valobj;

import cn.ethan.ai.domain.agent.adapter.port.IAgentStreamPort;
import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.valobj.enums.StreamTransportTypeEnumVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.Set;

/**
 * Agent 单次执行上下文，承载 Harness 执行过程中的共享运行状态。
 * 继承扳手树路由上下文仅用于满足框架泛型约束，业务代码统一使用本类字段。
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentExecutionContextVO extends cn.ethan.wrench.design.framework.tree.DynamicContext {

    /**
     * 当前流式展示步骤游标。
     */
    @Builder.Default
    private int streamStepCursor = 1;

    /**
     * 本次计划允许的最大步骤数。
     */
    @Builder.Default
    private int maxStep = 3;

    @Builder.Default
    private StreamTransportTypeEnumVO streamProtocol = StreamTransportTypeEnumVO.STREAMABLE_HTTP;

    private String sessionId;

    private IAgentStreamPort streamPort;

    private Map<String, AiAgentClientHarnessConfigVO> aiAgentClientHarnessConfigVOMap;

    private AgentRunAggregate agentRunAggregate;

    private Set<String> allowedTools;

    private String toolCapabilitySummary;

    private ToolRoutingDecisionVO toolRoutingDecision;

    private AgentContextBoundaryVO contextBoundary;

    private String supervisionResult;

    private boolean planValid;

    private boolean cancelled;

    public int nextStreamStepCursor() {
        int current = streamStepCursor;
        streamStepCursor++;
        return current;
    }

    public void bindSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String currentSessionId() {
        return sessionId;
    }
}
