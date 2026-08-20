package cn.ethan.core.agent.thread;

import org.junit.jupiter.api.Test;

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
        assertTrue(preserved.payloadJson().equals(envelope));
    }
}
