package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.adapter.port.IAgentStreamPort;
import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentActionVO;
import cn.ethan.ai.domain.agent.model.valobj.AgenticRagTraceVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextBudgetPolicyVO;
import cn.ethan.ai.domain.agent.model.valobj.HarnessObservationVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingItemVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentActionTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.harness.AgentActionParser;
import cn.ethan.ai.domain.agent.service.execute.harness.AgentActionPolicy;
import cn.ethan.ai.domain.agent.service.execute.harness.AgentHarnessExecuteService;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AgentHarnessSupportTest {

    private final AgentActionParser parser = new AgentActionParser();

    private final AgentActionPolicy policy = new AgentActionPolicy();

    @Test
    public void shouldParseFencedActionJson() {
        AgentActionVO action = parser.parse("""
                ```json
                {
                  "actionType": "RAG_RETRIEVE",
                  "query": "Spring AI MCP 如何接入",
                  "reason": "需要证据"
                }
                ```
                """, "fallback");

        Assert.assertEquals(AgentActionTypeEnumVO.RAG_RETRIEVE, action.getType());
        Assert.assertEquals("Spring AI MCP 如何接入", action.getQuery());
        Assert.assertFalse(action.hasParseError());
    }

    @Test
    public void shouldFallbackToLlmRespondWhenActionInvalid() {
        AgentActionVO action = parser.parse("not json", "请总结项目");

        Assert.assertEquals(AgentActionTypeEnumVO.LLM_RESPOND, action.getType());
        Assert.assertEquals("请总结项目", action.getQuery());
        Assert.assertTrue(action.hasParseError());
    }

    @Test
    public void shouldKeepOnlyReadOnlyEvidenceTools() {
        ToolRoutingDecisionVO original = ToolRoutingDecisionVO.builder()
                .enabled(true)
                .summary("mixed")
                .allowedToolNames(Set.of("web_search_exa", "get-library-docs", "send_notification", "create_memory"))
                .selectedMcpIds(Set.of("5001", "5002", "5005"))
                .selectedTools(List.of(
                        ToolRoutingItemVO.builder()
                                .mcpId("5001")
                                .mcpName("context7-docs")
                                .toolNames(List.of("get-library-docs"))
                                .routeTags(List.of("docs"))
                                .build(),
                        ToolRoutingItemVO.builder()
                                .mcpId("5002")
                                .mcpName("exa-search")
                                .toolNames(List.of("web_search_exa", "send_notification"))
                                .routeTags(List.of("search"))
                                .build()
                ))
                .build();

        ToolRoutingDecisionVO filtered = policy.readOnlyEvidenceDecision(original);

        Assert.assertTrue(filtered.isEnabled());
        Assert.assertTrue(filtered.getAllowedToolNames().contains("web_search_exa"));
        Assert.assertTrue(filtered.getAllowedToolNames().contains("get-library-docs"));
        Assert.assertFalse(filtered.getAllowedToolNames().contains("send_notification"));
        Assert.assertFalse(filtered.getAllowedToolNames().contains("create_memory"));
        Assert.assertTrue(filtered.getBlockedToolNames().contains("send_notification"));
    }

    @Test
    public void shouldRejectWhenActionLoopExceedsLimit() {
        AgentActionPolicy.PolicyCheckResult result = policy.validate(
                AgentActionVO.builder().type(AgentActionTypeEnumVO.LLM_RESPOND).build(),
                AgentActionPolicy.DEFAULT_MAX_ACTION_ROUNDS + 1,
                0,
                null
        );

        Assert.assertFalse(result.accepted());
        Assert.assertTrue(result.reason().contains("最大 Action Loop"));
    }

    @Test
    public void shouldEmitDedicatedRagEvidenceEventWithoutDuplicatingTraceInObservation() throws Exception {
        AgentHarnessExecuteService service = new AgentHarnessExecuteService();
        ExecuteCommandEntity command = ExecuteCommandEntity.builder()
                .aiAgentId("1")
                .sessionId("session-rag-event")
                .message("仅基于知识库回答")
                .build();
        AgentRunAggregate run = AgentRunAggregate.create(command, ContextBudgetPolicyVO.builder().build());
        CapturingStreamPort streamPort = new CapturingStreamPort();
        AgentExecutionContextVO executionContext = AgentExecutionContextVO.builder()
                .streamPort(streamPort)
                .build();
        AgenticRagTraceVO trace = AgenticRagTraceVO.builder()
                .originalQuestion("Spring AI MCP 如何接入")
                .build();
        HarnessObservationVO observation = HarnessObservationVO.builder()
                .actionId("action-1")
                .actionType(AgentActionTypeEnumVO.RAG_RETRIEVE)
                .success(true)
                .terminal(true)
                .message("基于证据的回答")
                .payload(Map.of(
                        "rag_evidence", trace,
                        "retrievalQueries", List.of("Spring AI MCP 如何接入")
                ))
                .build();

        Method method = AgentHarnessExecuteService.class.getDeclaredMethod(
                "sendObservation",
                ExecuteCommandEntity.class,
                AgentExecutionContextVO.class,
                AgentRunAggregate.class,
                HarnessObservationVO.class
        );
        method.setAccessible(true);
        method.invoke(service, command, executionContext, run, observation);

        Assert.assertEquals(2, streamPort.results.size());
        AgentExecuteResultEntity evidenceEvent = streamPort.results.get(0);
        Assert.assertEquals("rag_evidence", evidenceEvent.getSubType());
        Assert.assertSame(trace, evidenceEvent.getPayload());

        AgentExecuteResultEntity observationEvent = streamPort.results.get(1);
        Assert.assertEquals("harness_observation", observationEvent.getSubType());
        HarnessObservationVO streamedObservation = (HarnessObservationVO) observationEvent.getPayload();
        Assert.assertFalse(streamedObservation.getPayload().containsKey("rag_evidence"));
        Assert.assertEquals(List.of("Spring AI MCP 如何接入"), streamedObservation.getPayload().get("retrievalQueries"));
    }

    private static class CapturingStreamPort implements IAgentStreamPort {

        private final List<AgentExecuteResultEntity> results = new ArrayList<>();

        @Override
        public void send(AgentExecuteResultEntity result) {
            results.add(result);
        }

        @Override
        public void complete() {
        }

        @Override
        public void onTimeout(Runnable callback) {
        }

        @Override
        public void onCompletion(Runnable callback) {
        }
    }
}
