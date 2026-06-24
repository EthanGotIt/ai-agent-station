package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.adapter.port.IAgentStreamPort;
import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentActionVO;
import cn.ethan.ai.domain.agent.model.valobj.AgenticRagTraceVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextBudgetPolicyVO;
import cn.ethan.ai.domain.agent.model.valobj.EvidenceBoardVO;
import cn.ethan.ai.domain.agent.model.valobj.HarnessObservationVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingItemVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentActionTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.harness.AgentActionParser;
import cn.ethan.ai.domain.agent.service.execute.harness.AgentActionPolicy;
import cn.ethan.ai.domain.agent.service.execute.harness.HarnessEventPublisher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
                  "actionType": "RETRIEVE",
                  "sourceType": "OFFICIAL_DOCS",
                  "queries": ["Spring AI MCP 如何接入"],
                  "reason": "需要证据"
                }
                ```
                """, "fallback");

        Assertions.assertEquals(AgentActionTypeEnumVO.RETRIEVE, action.getType());
        Assertions.assertEquals("Spring AI MCP 如何接入", action.getQuery());
        Assertions.assertFalse(action.hasParseError());
    }

    @Test
    public void shouldFallbackToFinalizeForModelOnlyTaskWhenActionInvalid() {
        AgentActionVO action = parser.parse("not json", "请润色项目描述");

        Assertions.assertEquals(AgentActionTypeEnumVO.FINALIZE, action.getType());
        Assertions.assertEquals("请润色项目描述", action.getQuery());
        Assertions.assertTrue(action.hasParseError());
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

        Assertions.assertTrue(filtered.isEnabled());
        Assertions.assertTrue(filtered.getAllowedToolNames().contains("web_search_exa"));
        Assertions.assertTrue(filtered.getAllowedToolNames().contains("get-library-docs"));
        Assertions.assertFalse(filtered.getAllowedToolNames().contains("send_notification"));
        Assertions.assertFalse(filtered.getAllowedToolNames().contains("create_memory"));
        Assertions.assertTrue(filtered.getBlockedToolNames().contains("send_notification"));
    }

    @Test
    public void shouldRejectWhenActionLoopExceedsLimit() {
        AgentActionPolicy.PolicyCheckResult result = policy.validate(
                AgentActionVO.builder().type(AgentActionTypeEnumVO.FINALIZE).build(),
                AgentActionPolicy.DEFAULT_MAX_ACTION_ROUNDS + 1,
                0,
                new EvidenceBoardVO()
        );

        Assertions.assertFalse(result.accepted());
        Assertions.assertTrue(result.reason().contains("最大 Action Loop"));
    }

    @Test
    void shouldContinueAfterRejectedFinalizationWhileRetrievalBudgetRemains() {
        Assertions.assertTrue(policy.canContinueAfterRejectedFinalization(2, 4, 1));
        Assertions.assertFalse(policy.canContinueAfterRejectedFinalization(4, 4, 1));
        Assertions.assertFalse(policy.canContinueAfterRejectedFinalization(
                2, 4, AgentActionPolicy.DEFAULT_MAX_EVIDENCE_RETRIEVALS));
    }

    @Test
    public void shouldEmitDedicatedRagEvidenceEventWithoutDuplicatingTraceInObservation() throws Exception {
        HarnessEventPublisher publisher = new HarnessEventPublisher();
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
                .actionType(AgentActionTypeEnumVO.RETRIEVE)
                .success(true)
                .terminal(true)
                .message("基于证据的回答")
                .payload(Map.of(
                        "rag_evidence", trace,
                        "retrievalQueries", List.of("Spring AI MCP 如何接入")
                ))
                .build();

        publisher.observation(command, executionContext, run, observation);

        Assertions.assertEquals(2, streamPort.results.size());
        AgentExecuteResultEntity evidenceEvent = streamPort.results.get(0);
        Assertions.assertEquals("rag_evidence", evidenceEvent.getSubType());
        Assertions.assertSame(trace, evidenceEvent.getPayload());

        AgentExecuteResultEntity observationEvent = streamPort.results.get(1);
        Assertions.assertEquals("harness_observation", observationEvent.getSubType());
        HarnessObservationVO streamedObservation = (HarnessObservationVO) observationEvent.getPayload();
        Assertions.assertFalse(streamedObservation.getPayload().containsKey("rag_evidence"));
        Assertions.assertEquals(List.of("Spring AI MCP 如何接入"), streamedObservation.getPayload().get("retrievalQueries"));
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
