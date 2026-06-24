package cn.ethan.ai.test.evaluation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class RagEvaluationMetricsTest {

    @Test
    public void shouldCalculateDeterministicRetrievalAndAnswerMetrics() {
        RagEvaluationSupport.Metrics metrics = RagEvaluationSupport.calculate(List.of(
                result("1", true, 1, false, false, true, 1D, 3, 120),
                result("2", true, 2, false, false, true, 0.5D, 2, 300),
                result("3", false, 0, true, true, false, 0D, 1, 900),
                result("4", true, 5, true, false, true, 0.75D, 4, 500)
        ));

        Assertions.assertEquals(0.75D, metrics.sourceRoutingAccuracy(), 0.001D);
        Assertions.assertEquals(0.75D, metrics.hitAt5(), 0.001D);
        Assertions.assertEquals((1D + 0.5D + 0D + 0.2D) / 4D, metrics.mrr(), 0.001D);
        Assertions.assertEquals(0.75D, metrics.citationValidity(), 0.001D);
        Assertions.assertEquals(2D / 3D, metrics.refusalF1(), 0.001D);
        Assertions.assertEquals(0.5625D, metrics.keyPointCoverage(), 0.001D);
        Assertions.assertEquals(2.5D, metrics.averageModelCalls(), 0.001D);
        Assertions.assertEquals(900L, metrics.p95LatencyMillis());
    }

    @Test
    void shouldExcludeSessionAndRefusalCasesFromRetrievalMetrics() {
        RagEvaluationSupport.EvaluationResult retrieval = result(
                "retrieval", true, 1, false, false, true, 1D, 2, 100);
        RagEvaluationSupport.EvaluationResult session = new RagEvaluationSupport.EvaluationResult(
                "session", RagEvaluationSupport.RetrievalMode.ADAPTIVE_AGENTIC_RETRIEVAL,
                false, 0, false, false, false, false, 1D, -1D,
                1, 50, false, false, List.of(), List.of(), List.of(), "", "");

        RagEvaluationSupport.Metrics metrics = RagEvaluationSupport.calculate(List.of(retrieval, session));

        Assertions.assertEquals(1D, metrics.sourceRoutingAccuracy());
        Assertions.assertEquals(1D, metrics.hitAt5());
        Assertions.assertEquals(1D, metrics.citationValidity());
        Assertions.assertEquals(1D, metrics.keyPointCoverage());
    }

    @Test
    void shouldAcceptEitherGovernedExternalSourceForExternalEvaluationGroup() {
        Assertions.assertTrue(RagEvaluationSupport.sourceRouteCorrect(
                List.of("PROJECT_KNOWLEDGE", "OFFICIAL_DOCS"),
                List.of("PROJECT_KNOWLEDGE", "EXTERNAL")));
        Assertions.assertTrue(RagEvaluationSupport.sourceRouteCorrect(
                List.of("PROJECT_KNOWLEDGE", "WEB_RESEARCH"),
                List.of("PROJECT_KNOWLEDGE", "EXTERNAL")));
        Assertions.assertFalse(RagEvaluationSupport.sourceRouteCorrect(
                List.of("PROJECT_KNOWLEDGE"),
                List.of("PROJECT_KNOWLEDGE", "EXTERNAL")));
    }

    private RagEvaluationSupport.EvaluationResult result(String id,
                                                         boolean route,
                                                         int rank,
                                                         boolean shouldRefuse,
                                                         boolean refused,
                                                         boolean citationsValid,
                                                         double coverage,
                                                         int calls,
                                                         long latency) {
        return new RagEvaluationSupport.EvaluationResult(id,
                RagEvaluationSupport.RetrievalMode.ADAPTIVE_AGENTIC_RETRIEVAL,
                route, rank, true, shouldRefuse, refused, citationsValid, coverage, 1D,
                calls, latency, false, false,
                List.of(), List.of(), List.of(), "", "");
    }
}
