package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.adapter.port.IAgentModelPort;
import cn.ethan.ai.domain.agent.adapter.port.ILocalEvidenceRetrievalPort;
import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentRunTraceEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientHarnessConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentContextBoundaryVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextBudgetPolicyVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextWindowGuardVO;
import cn.ethan.ai.domain.agent.model.valobj.EvidenceBoardVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentActionVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolInvocationRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.EvidenceSourceTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.harness.EvidencePolicy;
import cn.ethan.ai.domain.agent.service.execute.harness.EvidenceRetrievalService;
import cn.ethan.ai.domain.agent.service.execute.harness.EvidenceTraceAssembler;
import cn.ethan.ai.domain.agent.service.execute.harness.GroundedAnswerService;
import cn.ethan.ai.domain.agent.service.execute.harness.McpEvidenceNormalizer;
import cn.ethan.ai.domain.agent.service.execute.runtime.PromptBudgetAssembler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.mockito.Mockito.mock;

public class EvidenceGovernanceTest {

    @Test
    public void shouldTreatAttributedMcpResultAsEvidence() {
        List<Document> documents = new McpEvidenceNormalizer().normalize(List.of(
                ToolInvocationRecordVO.builder().toolName("get-library-docs").success(true)
                        .output("{\"title\":\"Spring AI Reference\",\"url\":\"https://docs.spring.io/spring-ai\",\"content\":\"ToolContext carries contextual data.\"}")
                        .build()
        ), EvidenceSourceTypeEnumVO.OFFICIAL_DOCS, "ToolContext 是什么");

        Assertions.assertEquals(1, documents.size());
        Assertions.assertEquals("https://docs.spring.io/spring-ai", documents.get(0).getMetadata().get("uri"));
        Assertions.assertEquals(Boolean.TRUE, documents.get(0).getMetadata().get("qa_evidence_attributable"));
        Assertions.assertEquals("get-library-docs", documents.get(0).getMetadata().get("tool_name"));
    }

    @Test
    public void shouldExtractAttributionFromExaTextEnvelope() {
        String output = "[{\"text\":\"Title: Chat Client API :: Spring AI Reference\\n"
                + "URL: https://docs.spring.io/spring-ai/reference/api/chatclient.html\\n"
                + "Highlights: toolContext passes contextual data.\"}]";

        Document document = new McpEvidenceNormalizer().normalize(List.of(
                ToolInvocationRecordVO.builder().toolName("web_search_exa").success(true).output(output).build()
        ), EvidenceSourceTypeEnumVO.OFFICIAL_DOCS, "Spring AI toolContext").get(0);

        Assertions.assertEquals("https://docs.spring.io/spring-ai/reference/api/chatclient.html",
                document.getMetadata().get("uri"));
        Assertions.assertEquals("Chat Client API :: Spring AI Reference", document.getMetadata().get("title"));
        Assertions.assertEquals(Boolean.TRUE, document.getMetadata().get("qa_evidence_attributable"));
    }

    @Test
    public void shouldKeepPlainMcpTextAsLowTrustSupplementOnly() {
        Document document = new McpEvidenceNormalizer().normalize(List.of(
                ToolInvocationRecordVO.builder().toolName("web_search_exa").success(true).output("没有 URL 的模型整理文本").build()
        ), EvidenceSourceTypeEnumVO.WEB_RESEARCH, "latest").get(0);

        Assertions.assertEquals(Boolean.FALSE, document.getMetadata().get("qa_evidence_attributable"));
        EvidenceBoardVO board = new EvidenceBoardVO();
        board.addEvidence(List.of(document));
        Assertions.assertFalse(new EvidencePolicy().evaluateFinalization("最新版本是什么", board).allowed());
    }

    @Test
    public void shouldRequireExternalSourceForVersionedQuestion() {
        EvidenceBoardVO board = new EvidenceBoardVO();
        board.addEvidence(List.of(evidence("local", EvidenceSourceTypeEnumVO.PROJECT_KNOWLEDGE, true)));

        EvidencePolicy.Decision decision = new EvidencePolicy().evaluateFinalization("Spring AI 最新版本是什么", board);

        Assertions.assertFalse(decision.allowed());
        Assertions.assertTrue(decision.reason().contains("官方文档/外部资料"));
    }

    @Test
    void shouldRequireProjectAndExternalEvidenceForComparison() {
        EvidenceBoardVO board = new EvidenceBoardVO();
        board.addEvidence(List.of(evidence("local", EvidenceSourceTypeEnumVO.PROJECT_KNOWLEDGE, true)));

        EvidencePolicy.Decision rejected = new EvidencePolicy().evaluateFinalization(
                "项目的 toolContext 用法与外部规范是否一致？", board);
        board.addEvidence(List.of(evidence("official", EvidenceSourceTypeEnumVO.OFFICIAL_DOCS, true)));
        EvidencePolicy.Decision accepted = new EvidencePolicy().evaluateFinalization(
                "项目的 toolContext 用法与外部规范是否一致？", board);

        Assertions.assertFalse(rejected.allowed());
        Assertions.assertTrue(rejected.reason().contains("PROJECT_KNOWLEDGE"));
        Assertions.assertTrue(accepted.allowed());
    }

    @Test
    void shouldAnswerSessionFollowUpWithoutNewEvidence() {
        StubModelPort model = new StubModelPort("OFFICIAL_DOCS");
        GroundedAnswerService service = groundedService(model);
        ExecuteCommandEntity command = command("你刚才提到的第二个 evidence source 是什么？");
        AgentRunAggregate run = AgentRunAggregate.create(command, ContextBudgetPolicyVO.builder().build());
        AgentContextBoundaryVO boundary = AgentContextBoundaryVO.builder()
                .sessionContextSummary("最近完整对话 Turn：\nUSER：证据来源有哪些？\nASSISTANT：PROJECT_KNOWLEDGE、OFFICIAL_DOCS、WEB_RESEARCH。")
                .build();
        cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO context = context(run, new EvidenceBoardVO());
        context.setContextBoundary(boundary);

        GroundedAnswerService.Result result = service.finalizeAnswer(run, command, context, 1);

        Assertions.assertEquals("SESSION_CONTEXT", result.policy().groundingMode());
        Assertions.assertEquals("OFFICIAL_DOCS", result.answer());
        Assertions.assertEquals(1, model.calls);
        Assertions.assertTrue(model.lastPrompt.contains("不得在当前回答中复制或生成 Evidence 引用"));
    }

    @Test
    public void shouldDeduplicateSameEvidenceUri() {
        EvidenceBoardVO board = new EvidenceBoardVO();
        Document first = evidence("same", EvidenceSourceTypeEnumVO.OFFICIAL_DOCS, true);
        Document stronger = first.mutate().text("stronger").score(0.9D).build();

        Assertions.assertEquals(1, board.addEvidence(List.of(first, stronger)));
        Assertions.assertEquals(1, board.getEvidences().size());
        Assertions.assertEquals("stronger", board.getEvidences().get(0).getText());
    }

    @Test
    void shouldLimitMergedProjectEvidencePerRetrieveAction() {
        ILocalEvidenceRetrievalPort retrievalPort = request -> List.of(
                scoredEvidence(request.getQuery() + "-1", 0.9D),
                scoredEvidence(request.getQuery() + "-2", 0.8D),
                scoredEvidence(request.getQuery() + "-3", 0.7D),
                scoredEvidence(request.getQuery() + "-4", 0.6D));
        EvidenceRetrievalService service = new EvidenceRetrievalService(
                retrievalPort, mock(IAgentModelPort.class), mock(cn.ethan.ai.domain.agent.service.execute.harness.RuntimeToolCapabilityService.class),
                new cn.ethan.ai.domain.agent.service.execute.harness.AgentActionPolicy(), new McpEvidenceNormalizer());
        ExecuteCommandEntity command = command("项目如何治理 evidence");
        AgentRunAggregate run = AgentRunAggregate.create(command, ContextBudgetPolicyVO.builder().build());
        cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO context = context(run, new EvidenceBoardVO());
        context.setAiAgentClientHarnessConfigVOMap(Map.of("client", AiAgentClientHarnessConfigVO.builder()
                .ragIds(Set.of("7991")).build()));

        List<Document> documents = service.retrieve(run, command, context, AgentActionVO.builder()
                .sourceType(EvidenceSourceTypeEnumVO.PROJECT_KNOWLEDGE)
                .queries(List.of("query-a", "query-b"))
                .build(), 1).getDocuments();

        Assertions.assertEquals(4, documents.size());
        Assertions.assertEquals(List.of("query-a-1", "query-b-1", "query-a-2", "query-b-2"),
                documents.stream().map(Document::getId).toList());
    }

    @Test
    public void shouldRefuseFactualAnswerWithoutEvidence() {
        StubModelPort model = new StubModelPort("不应调用");
        GroundedAnswerService service = groundedService(model);
        ExecuteCommandEntity command = command("项目使用什么检索策略");
        AgentRunAggregate run = AgentRunAggregate.create(command, ContextBudgetPolicyVO.builder().build());

        GroundedAnswerService.Result result = service.finalizeAnswer(
                run, command, context(run, new EvidenceBoardVO()), 1);

        Assertions.assertTrue(result.answer().contains("证据不足"));
        Assertions.assertEquals(0, model.calls);
    }

    @Test
    public void shouldRepairCitationOnceAndAcceptValidEvidenceId() {
        StubModelPort model = new StubModelPort("没有引用的回答", "基于证据可确认该结论 [E1]");
        GroundedAnswerService service = groundedService(model);
        ExecuteCommandEntity command = command("ToolContext 的作用是什么");
        AgentRunAggregate run = AgentRunAggregate.create(command, ContextBudgetPolicyVO.builder().build());
        EvidenceBoardVO board = new EvidenceBoardVO();
        board.addEvidence(List.of(evidence("official", EvidenceSourceTypeEnumVO.OFFICIAL_DOCS, true)));

        GroundedAnswerService.Result result = service.finalizeAnswer(run, command, context(run, board), 1);

        Assertions.assertEquals(2, model.calls);
        Assertions.assertTrue(result.answer().contains("[E1]"));
    }

    private GroundedAnswerService groundedService(StubModelPort model) {
        return new GroundedAnswerService(model, new EvidencePolicy(), new EvidenceTraceAssembler(),
                new PromptBudgetAssembler(12000, null));
    }

    private cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO context(AgentRunAggregate run, EvidenceBoardVO board) {
        return cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO.builder()
                .agentRunAggregate(run).evidenceBoard(board).aiAgentClientHarnessConfigVOMap(Map.of()).build();
    }

    private ExecuteCommandEntity command(String message) {
        return ExecuteCommandEntity.builder().aiAgentId("1").sessionId("session-test").message(message).build();
    }

    private Document evidence(String id, EvidenceSourceTypeEnumVO sourceType, boolean attributable) {
        return Document.builder().id(id).text("可核验内容").score(0.8D).metadata(Map.of(
                "uri", "https://example.com/" + id,
                "title", id,
                "qa_evidence_source_type", sourceType.name(),
                "qa_evidence_attributable", attributable
        )).build();
    }

    private Document scoredEvidence(String id, double score) {
        return Document.builder().id(id).text(id).score(score).metadata(Map.of()).build();
    }

    private static class StubModelPort implements IAgentModelPort {

        private final Queue<String> answers = new ArrayDeque<>();
        private int calls;
        private String lastPrompt;

        private StubModelPort(String... answers) {
            this.answers.addAll(List.of(answers));
        }

        @Override
        public boolean hasAvailableModelClient(Map<String, AiAgentClientHarnessConfigVO> configs, AiClientTypeEnumVO... types) {
            return true;
        }

        @Override
        public String callModel(Map<String, AiAgentClientHarnessConfigVO> configs,
                                ExecuteCommandEntity command,
                                ContextWindowGuardVO guard,
                                AgentRunTraceEntity trace,
                                String prompt,
                                String eventType,
                                String stepId,
                                Integer step,
                                ToolRoutingDecisionVO tools,
                                AiClientTypeEnumVO... types) {
            calls++;
            lastPrompt = prompt;
            return answers.remove();
        }
    }
}
