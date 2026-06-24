package cn.ethan.ai.test.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class RagEvaluationSupport {

    private static final String DATASET = "/evaluation/rag-evaluation-v1.jsonl";

    private RagEvaluationSupport() {
    }

    static List<EvaluationCase> loadCases() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<EvaluationCase> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                java.util.Objects.requireNonNull(RagEvaluationSupport.class.getResourceAsStream(DATASET)),
                StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = mapper.readTree(line);
                result.add(new EvaluationCase(
                        node.path("id").asText(),
                        node.path("category").asText(),
                        node.path("question").asText(),
                        textArray(node.path("expectedSourceTypes")),
                        textArray(node.path("acceptableEvidence")),
                        textArray(node.path("answerKeyPoints")),
                        node.path("shouldRefuse").asBoolean(),
                        node.path("expectedStrategy").asText()
                ));
            }
        }
        return result;
    }

    static Metrics calculate(List<EvaluationResult> results) {
        if (results == null || results.isEmpty()) {
            return Metrics.empty();
        }
        int size = results.size();
        List<EvaluationResult> retrievalExpected = results.stream().filter(EvaluationResult::retrievalExpected).toList();
        double routing = retrievalExpected.isEmpty() ? 0D : retrievalExpected.stream()
                .filter(EvaluationResult::sourceRouteCorrect).count() / (double) retrievalExpected.size();
        double hit5 = retrievalExpected.isEmpty() ? 0D : retrievalExpected.stream()
                .filter(result -> result.firstRelevantRank() > 0 && result.firstRelevantRank() <= 5)
                .count() / (double) retrievalExpected.size();
        double mrr = retrievalExpected.stream().mapToDouble(result -> result.firstRelevantRank() <= 0
                ? 0D : 1D / result.firstRelevantRank()).average().orElse(0D);
        double citation = retrievalExpected.isEmpty() ? 0D : retrievalExpected.stream()
                .filter(EvaluationResult::citationsValid).count() / (double) retrievalExpected.size();
        long truePositive = results.stream().filter(result -> result.shouldRefuse() && result.refused()).count();
        long falsePositive = results.stream().filter(result -> !result.shouldRefuse() && result.refused()).count();
        long falseNegative = results.stream().filter(result -> result.shouldRefuse() && !result.refused()).count();
        double precision = truePositive == 0 ? 0D : truePositive / (double) (truePositive + falsePositive);
        double recall = truePositive == 0 ? 0D : truePositive / (double) (truePositive + falseNegative);
        double refusalF1 = precision + recall == 0D ? 0D : 2D * precision * recall / (precision + recall);
        double coverage = results.stream().mapToDouble(EvaluationResult::keyPointCoverage)
                .filter(value -> value >= 0D).average().orElse(0D);
        double faithfulness = results.stream().mapToDouble(EvaluationResult::faithfulness)
                .filter(value -> value >= 0D).average().orElse(0D);
        double calls = results.stream().mapToInt(EvaluationResult::modelCalls).average().orElse(0D);
        List<Long> sortedLatency = results.stream().map(EvaluationResult::latencyMillis).sorted().toList();
        int p95Index = Math.max(0, (int) Math.ceil(sortedLatency.size() * 0.95D) - 1);
        return new Metrics(size, routing, hit5, mrr, citation, refusalF1, coverage, faithfulness,
                calls, sortedLatency.get(p95Index));
    }

    static boolean sourceRouteCorrect(List<String> actual, List<String> expected) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        Set<String> normalized = actual == null ? Set.of() : actual.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return expected.stream().map(value -> value.toUpperCase(Locale.ROOT)).allMatch(source ->
                "EXTERNAL".equals(source)
                        ? normalized.contains("OFFICIAL_DOCS") || normalized.contains("WEB_RESEARCH")
                        : normalized.contains(source));
    }

    private static List<String> textArray(JsonNode node) {
        List<String> result = new ArrayList<>();
        node.forEach(item -> result.add(item.asText()));
        return result;
    }

    enum RetrievalMode {
        PGVECTOR_ONLY,
        FIXED_ADVANCED_RAG_BASELINE,
        ADAPTIVE_AGENTIC_RETRIEVAL
    }

    record EvaluationCase(String id,
                          String category,
                          String question,
                          List<String> expectedSourceTypes,
                          List<String> acceptableEvidence,
                          List<String> answerKeyPoints,
                          boolean shouldRefuse,
                          String expectedStrategy) {

        boolean isLocalRetrievalCase() {
            return "PROJECT_SEMANTIC".equals(category) || "EXACT_TERM".equals(category);
        }
    }

    record EvaluationResult(String id,
                            RetrievalMode mode,
                            boolean sourceRouteCorrect,
                            int firstRelevantRank,
                            boolean retrievalExpected,
                            boolean shouldRefuse,
                            boolean refused,
                            boolean citationsValid,
                            double keyPointCoverage,
                            double faithfulness,
                            int modelCalls,
                            long latencyMillis,
                            boolean secondRetrievalTriggered,
                            boolean secondRetrievalRecovered,
                            List<String> selectedSources,
                            List<String> evidenceSources,
                            List<String> evidencePreviews,
                            String answer,
                            String error) {
    }

    record Metrics(int caseCount,
                   double sourceRoutingAccuracy,
                   double hitAt5,
                   double mrr,
                   double citationValidity,
                   double refusalF1,
                   double keyPointCoverage,
                   double faithfulness,
                   double averageModelCalls,
                   long p95LatencyMillis) {

        static Metrics empty() {
            return new Metrics(0, 0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D, 0L);
        }
    }
}
