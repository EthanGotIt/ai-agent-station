package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentContextBoundaryVO;
import cn.ethan.ai.domain.agent.service.execute.flow.AgentContextBoundaryService;
import org.junit.Assert;
import org.junit.Test;

public class AgentContextBoundaryServiceTest {

    private final AgentContextBoundaryService agentContextBoundaryService = new AgentContextBoundaryService();

    @Test
    public void shouldIsolateConversationAndPreferenceScopeBySession() {
        AgentContextBoundaryVO sessionA = agentContextBoundaryService.buildBoundary(
                ExecuteCommandEntity.builder()
                        .sessionId("session-a")
                        .message("以后请用中文简洁回答")
                        .build()
        );
        AgentContextBoundaryVO sessionB = agentContextBoundaryService.buildBoundary(
                ExecuteCommandEntity.builder()
                        .sessionId("session-b")
                        .message("以后请用中文简洁回答")
                        .build()
        );

        Assert.assertNotEquals(sessionA.getSessionId(), sessionB.getSessionId());
        Assert.assertNotEquals(sessionA.getUserPreferenceScope(), sessionB.getUserPreferenceScope());
        Assert.assertNotEquals(sessionA.getConversationScope(), sessionB.getConversationScope());
        Assert.assertEquals("session:session-a:preferences", sessionA.getUserPreferenceScope());
        Assert.assertEquals("session:session-b:conversation_memory", sessionB.getConversationScope());
    }

    @Test
    public void shouldNotCarryPreferenceWhenNextRequestHasNoPreferenceMarker() {
        AgentContextBoundaryVO withPreference = agentContextBoundaryService.buildBoundary(
                "session-a",
                "以后请用中文简洁回答"
        );
        AgentContextBoundaryVO withoutPreference = agentContextBoundaryService.buildBoundary(
                "session-a",
                "解释一下当前项目架构"
        );

        Assert.assertFalse(withPreference.getUserPreferences().isEmpty());
        Assert.assertTrue(withoutPreference.getUserPreferences().isEmpty());
        Assert.assertEquals(withPreference.getUserPreferenceScope(), withoutPreference.getUserPreferenceScope());
    }

    @Test
    public void shouldKeepRunSummaryWhenAttached() {
        AgentContextBoundaryVO boundary = agentContextBoundaryService.buildBoundary(
                "session-summary",
                "总结资料"
        );

        AgentContextBoundaryService.attachRunSummary(boundary, "当前 Run 的步骤压缩摘要");

        Assert.assertEquals("当前 Run 的步骤压缩摘要", boundary.getRunContextSummary());
        Assert.assertTrue(AgentContextBoundaryService.buildPromptSection(boundary).contains("当前 Run 的步骤压缩摘要"));
    }

    @Test
    public void shouldExposeProjectRulesAndDisableLongTermMemoryByDefault() {
        AgentContextBoundaryVO boundary = agentContextBoundaryService.buildBoundary(
                "session-rule",
                "普通问答"
        );

        Assert.assertEquals("project:ai-agent-station", boundary.getProjectRuleScope());
        Assert.assertFalse(boundary.isLongTermMemoryEnabled());
        Assert.assertTrue(boundary.getProjectRules().stream().anyMatch(rule -> rule.contains("不得把其他 session")));
        Assert.assertTrue(AgentContextBoundaryService.buildPromptSection(boundary).contains("不得跨 session"));
    }
}
