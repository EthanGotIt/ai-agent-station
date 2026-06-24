package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.valobj.AgentConversationMessageVO;
import cn.ethan.ai.domain.agent.model.valobj.HeuristicContextUnitEstimator;
import cn.ethan.ai.domain.agent.model.valobj.SessionContextSnapshotVO;
import cn.ethan.ai.domain.agent.model.valobj.SessionMemorySummaryVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentConversationMessageRoleEnumVO;
import cn.ethan.ai.domain.agent.service.execute.runtime.SessionContextAssembler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SessionContextAssemblerTest {

    @Test
    public void shouldRenderOnlyCompleteTurns() {
        SessionContextAssembler assembler = new SessionContextAssembler();
        List<AgentConversationMessageVO> messages = new ArrayList<>(turn(1, "完整问题", "完整回答"));
        messages.add(message(3, "failed", AgentConversationMessageRoleEnumVO.USER, "失败请求"));

        SessionContextSnapshotVO snapshot = assembler.assemble(SessionMemorySummaryVO.builder().build(), messages);

        Assertions.assertTrue(snapshot.getContextSummary().contains("完整问题"));
        Assertions.assertFalse(snapshot.getContextSummary().contains("失败请求"));
        Assertions.assertEquals(2, snapshot.getMessageCount());
    }

    @Test
    public void shouldKeepFourCompleteTurns() {
        SessionContextAssembler assembler = new SessionContextAssembler();
        List<AgentConversationMessageVO> messages = turns(6, "问题", "回答");

        SessionContextSnapshotVO snapshot = assembler.assemble(SessionMemorySummaryVO.builder().build(), messages);

        Assertions.assertTrue(snapshot.isCompressed());
        Assertions.assertFalse(snapshot.getContextSummary().contains("问题1"));
        Assertions.assertTrue(snapshot.getContextSummary().contains("问题3"));
        Assertions.assertTrue(snapshot.getContextSummary().contains("问题6"));
        Assertions.assertEquals(8, snapshot.getRecentMessageCount());
    }

    @Test
    public void shouldEvictWholeTurnsWhenBudgetIsExceeded() {
        SessionContextAssembler assembler = new SessionContextAssembler(120, 4, HeuristicContextUnitEstimator.INSTANCE);

        SessionContextSnapshotVO snapshot = assembler.assemble(
                SessionMemorySummaryVO.builder().build(), turns(4, "很长问题".repeat(20), "很长回答".repeat(20)));

        Assertions.assertTrue(snapshot.isCompressed());
        Assertions.assertEquals(0, snapshot.getRecentMessageCount() % 2);
        Assertions.assertTrue(snapshot.getAssembledContextUnits() <= 120);
    }

    @Test
    public void shouldInjectStructuredSummaryBeforeRecentTurns() {
        SessionMemorySummaryVO summary = SessionMemorySummaryVO.builder()
                .goals(new ArrayList<>(List.of("完成 Java 项目知识核验")))
                .constraints(new ArrayList<>(List.of("只使用可引用证据")))
                .responsePreferences(new java.util.LinkedHashMap<>(Map.of("detail", "concise")))
                .build();

        SessionContextSnapshotVO snapshot = new SessionContextAssembler().assemble(summary, turn(1, "继续", "已继续"));

        Assertions.assertTrue(snapshot.getContextSummary().startsWith("Session 结构化摘要"));
        Assertions.assertTrue(snapshot.getContextSummary().contains("detail=concise"));
        Assertions.assertTrue(snapshot.getContextSummary().contains("最近完整对话 Turn"));
    }

    @Test
    public void shouldNotSplitOrTruncateSingleMessage() {
        SessionContextAssembler assembler = new SessionContextAssembler(50, 4, HeuristicContextUnitEstimator.INSTANCE);
        SessionContextSnapshotVO snapshot = assembler.assemble(
                SessionMemorySummaryVO.builder().build(), turn(1, "X".repeat(500), "Y".repeat(500)));

        Assertions.assertEquals(0, snapshot.getRecentMessageCount());
        Assertions.assertFalse(snapshot.getContextSummary().contains("XXX"));
    }

    private List<AgentConversationMessageVO> turns(int count, String userPrefix, String assistantPrefix) {
        List<AgentConversationMessageVO> messages = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            messages.addAll(turn(i, userPrefix + i, assistantPrefix + i));
        }
        return messages;
    }

    private List<AgentConversationMessageVO> turn(int index, String user, String assistant) {
        return List.of(
                message(index * 2L - 1, "run-" + index, AgentConversationMessageRoleEnumVO.USER, user),
                message(index * 2L, "run-" + index, AgentConversationMessageRoleEnumVO.ASSISTANT, assistant)
        );
    }

    private AgentConversationMessageVO message(long id,
                                               String runId,
                                               AgentConversationMessageRoleEnumVO role,
                                               String content) {
        return AgentConversationMessageVO.builder().id(id).runId(runId).role(role).content(content).build();
    }
}
