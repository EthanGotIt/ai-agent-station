package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentContextBoundaryVO;
import cn.ethan.ai.domain.agent.service.execute.runtime.AgentContextBoundaryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AgentContextBoundaryServiceTest {

    private final AgentContextBoundaryService agentContextBoundaryService = new AgentContextBoundaryService();

    @Test
    public void shouldIsolateConversationAndPreferenceScopeBySession() {
        AgentContextBoundaryVO sessionA = agentContextBoundaryService.buildBoundary(
                ExecuteCommandEntity.builder()
                        .sessionId("session-a")
                        .message("以后请用中文简洁回答")
                        .build(),
                null
        );
        AgentContextBoundaryVO sessionB = agentContextBoundaryService.buildBoundary(
                ExecuteCommandEntity.builder()
                        .sessionId("session-b")
                        .message("以后请用中文简洁回答")
                        .build(),
                null
        );

        Assertions.assertNotEquals(sessionA.getSessionId(), sessionB.getSessionId());
        Assertions.assertNotEquals(sessionA.getUserPreferenceScope(), sessionB.getUserPreferenceScope());
        Assertions.assertNotEquals(sessionA.getConversationScope(), sessionB.getConversationScope());
        Assertions.assertEquals("session:session-a:preferences", sessionA.getUserPreferenceScope());
        Assertions.assertEquals("session:session-b:conversation_memory", sessionB.getConversationScope());
    }

    @Test
    public void shouldNotCarryPreferenceWhenNextRequestHasNoPreferenceMarker() {
        AgentContextBoundaryVO withPreference = agentContextBoundaryService.buildBoundary(
                "session-a",
                "以后请用中文简洁回答",
                null
        );
        AgentContextBoundaryVO withoutPreference = agentContextBoundaryService.buildBoundary(
                "session-a",
                "解释一下当前项目架构",
                null
        );

        Assertions.assertFalse(withPreference.getUserPreferences().isEmpty());
        Assertions.assertTrue(withoutPreference.getUserPreferences().isEmpty());
        Assertions.assertEquals(withPreference.getUserPreferenceScope(), withoutPreference.getUserPreferenceScope());
    }

    @Test
    public void shouldInjectPersistedSessionSummaryWhenBoundaryIsBuilt() {
        AgentContextBoundaryVO boundary = agentContextBoundaryService.buildBoundary(
                "session-summary",
                "总结资料",
                "以下为历史消息摘要：上一轮最终回答"
        );

        Assertions.assertEquals("以下为历史消息摘要：上一轮最终回答", boundary.getSessionContextSummary());
        Assertions.assertTrue(AgentContextBoundaryService.buildPromptSection(boundary).contains("以下为历史消息摘要：上一轮最终回答"));
    }

    @Test
    public void shouldKeepSessionHistoryWithoutRunStepOutputs() {
        AgentContextBoundaryVO boundary = agentContextBoundaryService.buildBoundary(
                "session-summary",
                "总结资料",
                "上一轮用户输入和最终回答"
        );

        Assertions.assertEquals("上一轮用户输入和最终回答", boundary.getSessionContextSummary());
        Assertions.assertTrue(AgentContextBoundaryService.buildPromptSection(boundary).contains("上一轮用户输入和最终回答"));
        Assertions.assertFalse(AgentContextBoundaryService.buildPromptSection(boundary).contains("步骤压缩摘要"));
    }

    @Test
    public void shouldExposeProjectRulesAndDisableLongTermMemoryByDefault() {
        AgentContextBoundaryVO boundary = agentContextBoundaryService.buildBoundary(
                "session-rule",
                "普通问答",
                ""
        );

        Assertions.assertEquals("project:ai-agent-station", boundary.getProjectRuleScope());
        Assertions.assertFalse(boundary.isLongTermMemoryEnabled());
        Assertions.assertTrue(boundary.getProjectRules().stream().anyMatch(rule -> rule.contains("不得把其他 session")));
        Assertions.assertTrue(AgentContextBoundaryService.buildPromptSection(boundary).contains("不得跨 session"));
    }
}
