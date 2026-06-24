package cn.ethan.ai.test.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import cn.ethan.ai.test.evaluation.RagEvaluationSupport.RetrievalMode;

public class RagEvaluationDatasetTest {

    @Test
    public void shouldFreezeSixtyLabeledEvaluationCases() throws Exception {
        List<JsonNode> cases = loadRawCases();

        Assertions.assertEquals(60, cases.size());
        Assertions.assertEquals(60, cases.stream().map(node -> node.path("id").asText()).distinct().count());
        Map<String, Long> categories = cases.stream().collect(Collectors.groupingBy(
                node -> node.path("category").asText(), Collectors.counting()));
        Assertions.assertEquals(15L, categories.get("PROJECT_SEMANTIC"));
        Assertions.assertEquals(10L, categories.get("EXACT_TERM"));
        Assertions.assertEquals(10L, categories.get("OFFICIAL_DOCS"));
        Assertions.assertEquals(10L, categories.get("CROSS_SOURCE"));
        Assertions.assertEquals(10L, categories.get("NO_EVIDENCE_OR_CONFLICT"));
        Assertions.assertEquals(5L, categories.get("MEMORY_FOLLOWUP"));
        cases.forEach(this::assertRequiredLabels);
    }

    @Test
    public void shouldDefineAllThreeComparisonModesInTestCode() {
        Assertions.assertArrayEquals(new RetrievalMode[]{
                RetrievalMode.PGVECTOR_ONLY,
                RetrievalMode.FIXED_ADVANCED_RAG_BASELINE,
                RetrievalMode.ADAPTIVE_AGENTIC_RETRIEVAL
        }, RetrievalMode.values());
    }

    private List<JsonNode> loadRawCases() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return RagEvaluationSupport.loadCases().stream().<JsonNode>map(item -> mapper.valueToTree(Map.of(
                "id", item.id(),
                "question", item.question(),
                "category", item.category(),
                "expectedSourceTypes", item.expectedSourceTypes(),
                "acceptableEvidence", item.acceptableEvidence(),
                "answerKeyPoints", item.answerKeyPoints(),
                "shouldRefuse", item.shouldRefuse(),
                "expectedStrategy", item.expectedStrategy()
        ))).toList();
    }

    private void assertRequiredLabels(JsonNode node) {
        Assertions.assertFalse(node.path("question").asText().isBlank());
        Assertions.assertTrue(node.path("expectedSourceTypes").isArray());
        Assertions.assertTrue(node.path("acceptableEvidence").isArray());
        Assertions.assertTrue(node.path("answerKeyPoints").isArray());
        Assertions.assertTrue(node.has("shouldRefuse"));
        Assertions.assertFalse(node.path("expectedStrategy").asText().isBlank());
    }

}
