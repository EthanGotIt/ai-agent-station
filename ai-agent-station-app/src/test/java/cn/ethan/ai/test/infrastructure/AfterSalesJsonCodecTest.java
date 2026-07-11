package cn.ethan.ai.test.infrastructure;

import cn.ethan.ai.domain.agent.model.plan.RefundPlan;
import cn.ethan.ai.infrastructure.json.AfterSalesJsonCodec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;

public class AfterSalesJsonCodecTest {

    private final AfterSalesJsonCodec codec = AfterSalesJsonCodec.defaultCodec();

    @Test
    void shouldReadStructuredRefundPlanAndRoundTrip() {
        String modelJson = """
                {
                  "schemaVersion": 1,
                  "readyToEvaluate": false,
                  "evidenceGaps": [{"field":"orderStatus","source":"TOOL","reasonCode":"MISSING_ORDER_STATUS"}],
                  "steps": [{
                    "action":"TOOL_CALL",
                    "targetField":"orderStatus",
                    "toolName":"query_order",
                    "input":{"orderId":"ORDER-1"},
                    "reasonCode":"MISSING_ORDER_STATUS",
                    "expectedEvidence":"orderStatus"
                  }],
                  "checklist": [{"item":"orderStatus","status":"PENDING"}]
                }
                """;

        RefundPlan plan = codec.read(modelJson, RefundPlan.class, "test plan");
        Assertions.assertEquals("ORDER-1", plan.steps().get(0).input().get("orderId"));

        String persisted = codec.write(plan, "test persisted plan");
        RefundPlan restored = codec.read(persisted, RefundPlan.class, "test restored plan");
        Assertions.assertEquals(plan, restored);
    }

    @Test
    void shouldReadLegacyCheckpointPayloadWithoutMigration() {
        String legacyPayload = """
                {"userId":"user-1","daysSinceDelivery":7,"ready":true,"evidence":["ORDER"]}
                """;

        Map<String, Object> state = codec.read(legacyPayload, new TypeReference<>() {
        }, "test checkpoint");

        Assertions.assertEquals("user-1", state.get("userId"));
        Assertions.assertEquals(7, ((Number) state.get("daysSinceDelivery")).intValue());
        Assertions.assertEquals(true, state.get("ready"));
        Assertions.assertEquals(List.of("ORDER"), state.get("evidence"));
    }

    @Test
    void shouldKeepEventFieldsAndWrapInvalidJson() {
        Map<String, Object> event = Map.of(
                "caseId", "case-1",
                "commandId", "command-1",
                "orderId", "ORDER-1"
        );
        String json = codec.write(event, "test event");
        Map<String, Object> restored = codec.read(json, new TypeReference<>() {
        }, "test event");
        Assertions.assertEquals(event, restored);

        IllegalArgumentException error = Assertions.assertThrows(IllegalArgumentException.class,
                () -> codec.read("not-json", RefundPlan.class, "parse plan"));
        Assertions.assertTrue(error.getMessage().contains("parse plan"));
        Assertions.assertFalse(error.getCause().getClass().getName().startsWith("com.alibaba"));
    }
}
