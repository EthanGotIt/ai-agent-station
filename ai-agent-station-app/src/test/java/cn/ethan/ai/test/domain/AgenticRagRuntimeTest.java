package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.adapter.port.IAgentModelPort;
import cn.ethan.ai.domain.agent.adapter.port.IRagRetrievalPort;
import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentModelCallResultEntity;
import cn.ethan.ai.domain.agent.model.entity.AgentRunTraceEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.model.valobj.AgenticRagTraceVO;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientHarnessConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextBudgetPolicyVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextWindowGuardVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.harness.AgentActionPolicy;
import cn.ethan.ai.domain.agent.service.execute.harness.AgenticRagRuntime;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgenticRagRuntimeTest {

    @Test
    public void shouldTriggerOneSecondRetrievalWhenEvidenceInsufficient() throws Exception {
        StubRetrievalPort retrievalPort = new StubRetrievalPort(List.of(
                List.of(),
                List.of(Document.builder()
                        .id("doc-1")
                        .text("Spring AI MCP Client 接入需要先配置 MCP Server，再通过 ToolCallback 注入模型执行阶段。".repeat(4))
                        .metadata(Map.of(
                                "doc_id", "spring-ai",
                                "chunk_id", "chunk-1",
                                "qa_retrieval_source", "pgvector",
                                "qa_retrieval_query", "Spring AI MCP Client"
                        ))
                        .score(0.8D)
                        .build())
        ));
        AgenticRagRuntime runtime = runtime(retrievalPort, new StubModelPort("基于证据回答：MCP 工具应在执行阶段按权限注入。"));

        AgentModelCallResultEntity result = runtime.execute(
                run(),
                command("Spring AI MCP Client 怎么接入"),
                context(),
                "Spring AI MCP Client 怎么接入",
                ToolRoutingDecisionVO.disabled("no mcp"),
                1
        );

        AgenticRagTraceVO trace = (AgenticRagTraceVO) result.getMetadata().get(AgenticRagRuntime.METADATA_TRACE);
        Assert.assertEquals("基于证据回答：MCP 工具应在执行阶段按权限注入。", result.getContent());
        Assert.assertTrue(trace.isSecondRetrievalTriggered());
        Assert.assertEquals(2, trace.getRetrievalRounds().size());
        Assert.assertEquals(1, trace.getFinalEvidences().size());
        Assert.assertEquals(2, retrievalPort.queries.size());
    }

    @Test
    public void shouldReturnNoEvidenceAnswerWhenRetrievalEmpty() throws Exception {
        AgenticRagRuntime runtime = runtime(new StubRetrievalPort(List.of(List.of(), List.of())), new StubModelPort("should not use"));

        AgentModelCallResultEntity result = runtime.execute(
                run(),
                command("不存在的内部知识"),
                context(),
                "不存在的内部知识",
                ToolRoutingDecisionVO.disabled("no mcp"),
                1
        );

        AgenticRagTraceVO trace = (AgenticRagTraceVO) result.getMetadata().get(AgenticRagRuntime.METADATA_TRACE);
        Assert.assertTrue(result.getContent().contains("未能从当前知识库"));
        Assert.assertFalse(trace.isEvidenceSufficient());
        Assert.assertTrue(trace.getFinalEvidences().isEmpty());
        Assert.assertNotNull(trace.getNoEvidenceReason());
    }

    private AgenticRagRuntime runtime(IRagRetrievalPort retrievalPort, IAgentModelPort modelPort) throws Exception {
        AgenticRagRuntime runtime = new AgenticRagRuntime();
        inject(runtime, "ragRetrievalPort", retrievalPort);
        inject(runtime, "agentModelPort", modelPort);
        inject(runtime, "actionPolicy", new AgentActionPolicy());
        return runtime;
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private AgentRunAggregate run() {
        return AgentRunAggregate.create(command("test"), ContextBudgetPolicyVO.builder().build());
    }

    private ExecuteCommandEntity command(String message) {
        return ExecuteCommandEntity.builder()
                .aiAgentId("3")
                .sessionId("s1")
                .message(message)
                .maxStep(4)
                .build();
    }

    private AgentExecutionContextVO context() {
        AgentExecutionContextVO context = new AgentExecutionContextVO();
        context.setAiAgentClientHarnessConfigVOMap(Map.of("DEFAULT", AiAgentClientHarnessConfigVO.builder()
                .clientId("2103")
                .clientType("DEFAULT")
                .build()));
        return context;
    }

    private static class StubRetrievalPort implements IRagRetrievalPort {

        private final List<List<Document>> responses;

        private final List<String> queries = new ArrayList<>();

        private StubRetrievalPort(List<List<Document>> responses) {
            this.responses = responses;
        }

        @Override
        public List<Document> retrieve(SearchRequest searchRequest, Map<String, Object> context) {
            queries.add(searchRequest.getQuery());
            int index = Math.min(queries.size() - 1, responses.size() - 1);
            return responses.get(index);
        }
    }

    private static class StubModelPort implements IAgentModelPort {

        private final String content;

        private StubModelPort(String content) {
            this.content = content;
        }

        @Override
        public boolean hasAvailableModelClient(Map<String, AiAgentClientHarnessConfigVO> harnessConfigMap, AiClientTypeEnumVO... clientTypes) {
            return true;
        }

        @Override
        public String callModel(Map<String, AiAgentClientHarnessConfigVO> harnessConfigMap,
                                ExecuteCommandEntity command,
                                ContextWindowGuardVO contextWindowGuard,
                                AgentRunTraceEntity trace,
                                String prompt,
                                String eventType,
                                String stepId,
                                Integer step,
                                ToolRoutingDecisionVO toolRoutingDecision,
                                AiClientTypeEnumVO... clientTypes) {
            return content;
        }
    }
}
