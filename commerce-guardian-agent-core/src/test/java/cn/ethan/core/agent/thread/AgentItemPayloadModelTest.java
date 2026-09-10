package cn.ethan.core.agent.thread;

import cn.ethan.core.agent.execution.AgentTurnItemPayloads;
import cn.ethan.core.agent.workflow.AgentQuestionCardModel;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointModel;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStatusEnum;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item 协议测试：确认历史文本会被包成可判别、可版本化的 payload。
 *
 * @author ethan
 * @date 2026-08-20
 */
class AgentItemPayloadModelTest {

    @Test
    void wrapsTextAndPreservesJsonEnvelope() {
        AgentItemModel text = new AgentItemModel("item-1", "thread-1", "turn-1", 1,
                AgentItemTypeEnum.ASSISTANT_MESSAGE, "完成\n订单", java.time.Instant.EPOCH);
        assertTrue(text.payloadJson().startsWith("{\"schemaVersion\":1,\"kind\":\"ASSISTANT_MESSAGE\""));
        assertTrue(text.payloadJson().contains("完成\\n订单"));

        String envelope = "{\"schemaVersion\":1,\"kind\":\"ERROR\",\"data\":\"失败\"}";
        AgentItemModel preserved = new AgentItemModel("item-2", "thread-1", "turn-1", 2,
                AgentItemTypeEnum.ERROR, envelope, java.time.Instant.EPOCH);
        assertEquals(envelope, preserved.payloadJson());
    }

    @Test
    void interactionPayloadsUseDistinctItemKinds() {
        AgentQuestionCardModel question = AgentQuestionCardModel.agent(
                "question-1", "thread-1", "turn-1", "user-1", "补充信息", "请补充", "[]", List.of(), Instant.EPOCH);
        AgentWorkflowCheckpointModel checkpoint = new AgentWorkflowCheckpointModel(
                "checkpoint-1", "run-1", "thread-1", "turn-1", "user-1", "AUTHORIZE", "REFUND",
                "ORDER-1", "退款", "facts-v1", 0, AgentWorkflowCheckpointStatusEnum.OPEN, null,
                Instant.EPOCH, null);

        assertTrue(AgentTurnItemPayloads.questionCard(question).contains("\"kind\":\"QUESTION_CARD\""));
        assertTrue(AgentTurnItemPayloads.workflowCheckpoint(checkpoint)
                .contains("\"kind\":\"WORKFLOW_CHECKPOINT\""));
    }

    @Test
    void escapesAllJsonControlCharacters() {
        String escaped = AgentItemPayloadModel.escapeJson("\"\\\b\f\n\r\t\u0000\u001f");

        assertEquals("\\\"\\\\\\b\\f\\n\\r\\t\\u0000\\u001f", escaped);
        AgentItemModel item = new AgentItemModel("item-control", "thread-1", "turn-1", 3,
                AgentItemTypeEnum.ASSISTANT_MESSAGE, "\"\\\b\f\n\r\t\u0000\u001f", Instant.EPOCH);
        assertTrue(item.payloadJson().contains("\\u0000"));
        assertTrue(item.payloadJson().contains("\\u001f"));
        assertTrue(item.payloadJson().contains("\\b"));
        assertTrue(item.payloadJson().contains("\\f"));
    }
}
