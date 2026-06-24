package cn.ethan.ai.rag;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

public class PgVectorEvidenceRetrievalPortTest {

    @Test
    void shouldBuildScopedFilterForAllowedKnowledgeBases() {
        String filter = PgVectorEvidenceRetrievalPort.buildRagFilterExpression(Set.of("7001", "7002"));

        Assertions.assertTrue(filter.contains("rag_id == '7001'"));
        Assertions.assertTrue(filter.contains("rag_id == '7002'"));
        Assertions.assertTrue(filter.contains(" || "));
    }

    @Test
    void shouldEscapeRagIdLiteral() {
        String filter = PgVectorEvidenceRetrievalPort.buildRagFilterExpression(Set.of("7'001"));

        Assertions.assertEquals("(rag_id == '7''001')", filter);
    }
}
