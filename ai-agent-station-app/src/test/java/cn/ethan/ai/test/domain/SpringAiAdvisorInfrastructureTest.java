package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.valobj.AgenticRagTraceVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolInvocationCollector;
import cn.ethan.ai.domain.agent.model.valobj.ToolInvocationRecordVO;
import cn.ethan.ai.domain.agent.service.execute.springai.advisor.ContextBudgetAdvisor;
import cn.ethan.ai.domain.agent.service.execute.springai.advisor.EvidenceAccumulator;
import cn.ethan.ai.domain.agent.service.execute.springai.advisor.ObservationTraceAdvisor;
import cn.ethan.ai.domain.agent.service.execute.springai.advisor.SpringAiAdvisorContextKeys;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

public class SpringAiAdvisorInfrastructureTest {

    @Test
    public void contextBudgetAdvisorShouldStoreEstimatedUnits() {
        ContextBudgetAdvisor advisor = new ContextBudgetAdvisor(100, 0.95D, text -> 10);

        ChatClientRequest next = advisor.before(new ChatClientRequest(new Prompt("hello"), Map.of()), chain());

        Assertions.assertEquals(10, next.context().get(SpringAiAdvisorContextKeys.CONTEXT_UNITS));
        Assertions.assertEquals(Boolean.FALSE, next.context().get(SpringAiAdvisorContextKeys.CONTEXT_BUDGET_REJECTED));
    }

    @Test
    public void contextBudgetAdvisorShouldRejectWhenStopThresholdIsReached() {
        ContextBudgetAdvisor advisor = new ContextBudgetAdvisor(100, 0.95D, text -> 95);

        Assertions.assertThrows(IllegalStateException.class,
                () -> advisor.before(new ChatClientRequest(new Prompt("large"), Map.of()), chain()));
    }

    @Test
    public void evidenceAccumulatorShouldDeduplicateByUri() {
        EvidenceAccumulator accumulator = new EvidenceAccumulator();
        Document first = Document.builder()
                .text("Spring AI Tool Calling")
                .metadata(Map.of("uri", "https://docs.spring.io/spring-ai", "title", "Spring AI"))
                .score(0.9D)
                .build();
        Document duplicated = Document.builder()
                .text("Spring AI Tool Calling duplicate")
                .metadata(Map.of("uri", "https://docs.spring.io/spring-ai", "title", "Spring AI"))
                .score(0.7D)
                .build();

        int added = accumulator.addDocuments(List.of(first, duplicated));

        Assertions.assertEquals(1, added);
        Assertions.assertEquals(1, accumulator.snapshot().size());
        Assertions.assertEquals("E1", accumulator.snapshot().get(0).getEvidenceId());
    }

    @Test
    public void observationTraceAdvisorShouldBuildTraceFromToolInvocationContext() {
        ObservationTraceAdvisor advisor = new ObservationTraceAdvisor();
        ChatClientRequest request = new ChatClientRequest(new Prompt("search docs"), Map.of());
        ChatClientRequest withAccumulator = advisor.before(request, chain());
        ToolInvocationRecordVO invocation = ToolInvocationRecordVO.builder()
                .toolName("web_search_exa")
                .success(true)
                .output("""
                        {"title":"Spring AI Docs","url":"https://docs.spring.io/spring-ai","content":"Tool Calling Advisor"}
                        """)
                .build();
        ChatClientResponse response = new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage("done")))),
                Map.of(
                        SpringAiAdvisorContextKeys.EVIDENCE_ACCUMULATOR,
                        withAccumulator.context().get(SpringAiAdvisorContextKeys.EVIDENCE_ACCUMULATOR),
                        ToolInvocationCollector.METADATA_KEY,
                        List.of(invocation)
                ));

        ChatClientResponse traced = advisor.after(response, chain());

        Object traceObject = traced.context().get(SpringAiAdvisorContextKeys.RAG_EVIDENCE_TRACE);
        Assertions.assertInstanceOf(AgenticRagTraceVO.class, traceObject);
        AgenticRagTraceVO trace = (AgenticRagTraceVO) traceObject;
        Assertions.assertTrue(trace.isEvidenceSufficient());
        Assertions.assertEquals(1, trace.getFinalEvidences().size());
        Assertions.assertEquals("https://docs.spring.io/spring-ai", trace.getFinalEvidences().get(0).getUri());
    }

    private AdvisorChain chain() {
        return new AdvisorChain() {
            @Override
            public io.micrometer.observation.ObservationRegistry getObservationRegistry() {
                return io.micrometer.observation.ObservationRegistry.NOOP;
            }
        };
    }
}
