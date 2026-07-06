package cn.ethan.ai.test.infrastructure;

import cn.ethan.ai.domain.agent.model.plan.PlannedStep;
import cn.ethan.ai.domain.agent.model.plan.PlanningContext;
import cn.ethan.ai.domain.agent.model.plan.RefundPlan;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RefundPlanningAgent} 单元测试。
 */
public class RefundPlanningAgentTest {

    @Test
    void shouldReturnDeterministicPlanWhenChatClientIsNull() {
        RefundPlanningAgent agent = new RefundPlanningAgent(null);
        PlanningContext context = contextWithOrderId("ORDER-1");

        RefundPlan plan = agent.plan(context);

        Assertions.assertNotNull(plan);
        Assertions.assertFalse(plan.readyToEvaluate());
        Assertions.assertTrue(plan.steps().stream()
                .anyMatch(s -> "TOOL_CALL".equals(s.action()) && "query_order".equals(s.toolName())));
    }

    @Test
    void shouldUseFallbackWhenModelReturnsBlankContent() {
        RefundPlanningAgent agent = new RefundPlanningAgent(chatClientReturning(""));
        PlanningContext context = contextWithOrderId(null);

        RefundPlan plan = agent.plan(context);

        Assertions.assertNotNull(plan);
        Assertions.assertFalse(plan.readyToEvaluate());
        Assertions.assertTrue(plan.steps().stream()
                .anyMatch(s -> "ASK_USER".equals(s.action()) && "orderId".equals(s.targetField())));
    }

    @Test
    void shouldUseFallbackWhenModelReturnsInvalidJson() {
        RefundPlanningAgent agent = new RefundPlanningAgent(chatClientReturning("not a json plan"));
        PlanningContext context = contextWithOrderId(null);

        RefundPlan plan = agent.plan(context);

        Assertions.assertNotNull(plan);
        Assertions.assertFalse(plan.readyToEvaluate());
        Assertions.assertTrue(plan.steps().stream()
                .anyMatch(s -> "ASK_USER".equals(s.action()) && "orderId".equals(s.targetField())));
    }

    @Test
    void shouldParseValidPlanFromModel() {
        String json = """
                {
                  "readyToEvaluate": false,
                  "steps": [
                    {"action": "TOOL_CALL", "targetField": "orderStatus", "toolName": "query_order", "input": {"orderId": "ORDER-1"}}
                  ],
                  "checklist": [
                    {"item": "userId", "status": "DONE"},
                    {"item": "orderId", "status": "DONE"},
                    {"item": "orderStatus", "status": "PENDING"},
                    {"item": "refundReason", "status": "DONE"}
                  ]
                }
                """;
        RefundPlanningAgent agent = new RefundPlanningAgent(chatClientReturning(json));
        PlanningContext context = contextWithOrderId("ORDER-1");

        RefundPlan plan = agent.plan(context);

        Assertions.assertNotNull(plan);
        Assertions.assertFalse(plan.readyToEvaluate());
        Assertions.assertEquals(1, plan.steps().size());
        PlannedStep step = plan.steps().get(0);
        Assertions.assertEquals("TOOL_CALL", step.action());
        Assertions.assertEquals("query_order", step.toolName());
        Assertions.assertEquals("ORDER-1", step.input().get("orderId"));
    }

    private PlanningContext contextWithOrderId(String orderId) {
        return new PlanningContext(
                "user-1",
                "session-1",
                "退款",
                orderId,
                null,
                "DAMAGED",
                null,
                null,
                0,
                0,
                null,
                null
        );
    }

    private ChatClient chatClientReturning(String content) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class,
                org.mockito.Mockito.RETURNS_SELF);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.call()).thenReturn(response);
        when(response.content()).thenReturn(content);
        return chatClient;
    }
}
