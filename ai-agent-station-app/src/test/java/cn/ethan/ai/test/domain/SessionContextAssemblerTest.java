package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.valobj.AgentConversationMessageVO;
import cn.ethan.ai.domain.agent.model.valobj.HeuristicContextUnitEstimator;
import cn.ethan.ai.domain.agent.model.valobj.SessionContextSnapshotVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentConversationMessageRoleEnumVO;
import cn.ethan.ai.domain.agent.service.execute.flow.SessionContextAssembler;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class SessionContextAssemblerTest {

    @Test
    public void shouldKeepRecentMessagesAsOriginalWhenBelowThreshold() {
        SessionContextAssembler assembler = new SessionContextAssembler();

        SessionContextSnapshotVO snapshot = assembler.assemble(List.of(
                message(AgentConversationMessageRoleEnumVO.USER, "上一轮用户问题"),
                message(AgentConversationMessageRoleEnumVO.ASSISTANT, "上一轮最终回答")
        ));

        Assert.assertFalse(snapshot.isCompressed());
        Assert.assertTrue(snapshot.getContextSummary().contains("上一轮用户问题"));
        Assert.assertTrue(snapshot.getContextSummary().contains("上一轮最终回答"));
        Assert.assertEquals(2, snapshot.getRecentMessageCount());
    }

    @Test
    public void shouldCompactOlderMessagesAndKeepLatestFourMessagesAsOriginal() {
        SessionContextAssembler assembler = new SessionContextAssembler(
                240,
                0.50D,
                4,
                24,
                HeuristicContextUnitEstimator.INSTANCE
        );

        SessionContextSnapshotVO snapshot = assembler.assemble(List.of(
                message(AgentConversationMessageRoleEnumVO.USER, "较早问题甲".repeat(40)),
                message(AgentConversationMessageRoleEnumVO.ASSISTANT, "较早回答乙".repeat(40)),
                message(AgentConversationMessageRoleEnumVO.USER, "最近问题一"),
                message(AgentConversationMessageRoleEnumVO.ASSISTANT, "最近回答一"),
                message(AgentConversationMessageRoleEnumVO.USER, "最近问题二"),
                message(AgentConversationMessageRoleEnumVO.ASSISTANT, "最近回答二")
        ));

        Assert.assertTrue(snapshot.isCompressed());
        Assert.assertTrue(snapshot.getContextSummary().contains("同一 session 较早消息摘要"));
        Assert.assertTrue(snapshot.getContextSummary().contains("同一 session 最近消息原文"));
        Assert.assertTrue(snapshot.getContextSummary().contains("最近问题一"));
        Assert.assertTrue(snapshot.getContextSummary().contains("最近回答二"));
        Assert.assertEquals(4, snapshot.getRecentMessageCount());
        Assert.assertTrue(snapshot.getAssembledContextUnits() <= 240);
    }

    @Test
    public void shouldUsePersistedContextUnitsWhenDecidingWhetherToCompact() {
        SessionContextAssembler assembler = new SessionContextAssembler(
                100,
                0.50D,
                4,
                24,
                HeuristicContextUnitEstimator.INSTANCE
        );
        AgentConversationMessageVO message = message(AgentConversationMessageRoleEnumVO.USER, "短消息");
        message.setContextUnits(80);

        SessionContextSnapshotVO snapshot = assembler.assemble(List.of(message));

        Assert.assertTrue(snapshot.isCompressed());
        Assert.assertTrue(snapshot.getOriginalContextUnits() >= 80);
    }

    private AgentConversationMessageVO message(AgentConversationMessageRoleEnumVO role, String content) {
        return AgentConversationMessageVO.builder()
                .role(role)
                .content(content)
                .contentSummary(content.substring(0, Math.min(content.length(), 24)))
                .build();
    }

}
