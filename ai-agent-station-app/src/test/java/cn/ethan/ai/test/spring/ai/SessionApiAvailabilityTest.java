package cn.ethan.ai.test.spring.ai;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 固化 Spring AI 2 与社区 Session 的可用 API，避免记忆迁移时基于错误假设开发。
 */
public class SessionApiAvailabilityTest {

    private static final List<String> OFFICIAL_SESSION_API_CANDIDATES = List.of(
            "org.springframework.ai.chat.memory.Session",
            "org.springframework.ai.chat.memory.SessionEvent",
            "org.springframework.ai.chat.memory.SessionService",
            "org.springframework.ai.chat.memory.CompactionTrigger",
            "org.springframework.ai.chat.memory.CompactionStrategy"
    );

    @Test
    public void officialSpringAi2GaShouldNotBeTreatedAsNativeSessionApi() {
        List<String> missingSessionApis = OFFICIAL_SESSION_API_CANDIDATES.stream()
                .filter(className -> !classExists(className))
                .toList();

        Assertions.assertEquals(OFFICIAL_SESSION_API_CANDIDATES, missingSessionApis,
                "Spring AI 2.0.0 GA does not expose these APIs from org.springframework.ai.chat.memory.");
        Assertions.assertTrue(classExists("org.springframework.ai.chat.memory.ChatMemory"));
        Assertions.assertTrue(classExists("org.springframework.ai.chat.memory.ChatMemoryRepository"));
        Assertions.assertTrue(classExists("org.springframework.ai.chat.memory.MessageWindowChatMemory"));
        Assertions.assertTrue(classExists("org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor"));
    }

    @Test
    public void springAiCommunitySessionShouldExposeSessionAndCompactionApis() {
        Assertions.assertTrue(classExists("org.springframework.ai.session.Session"));
        Assertions.assertTrue(classExists("org.springframework.ai.session.SessionEvent"));
        Assertions.assertTrue(classExists("org.springframework.ai.session.SessionService"));
        Assertions.assertTrue(classExists("org.springframework.ai.session.DefaultSessionService"));
        Assertions.assertTrue(classExists("org.springframework.ai.session.InMemorySessionRepository"));
        Assertions.assertTrue(classExists("org.springframework.ai.session.advisor.SessionMemoryAdvisor"));
        Assertions.assertTrue(classExists("org.springframework.ai.session.compaction.CompactionTrigger"));
        Assertions.assertTrue(classExists("org.springframework.ai.session.compaction.CompactionStrategy"));
        Assertions.assertTrue(classExists("org.springframework.ai.session.compaction.TurnCountTrigger"));
        Assertions.assertTrue(classExists("org.springframework.ai.session.compaction.TokenCountTrigger"));
        Assertions.assertTrue(classExists("org.springframework.ai.session.compaction.TurnWindowCompactionStrategy"));
        Assertions.assertTrue(classExists("org.springframework.ai.session.compaction.RecursiveSummarizationCompactionStrategy"));
    }

    @Test
    public void springAi2ShouldExposeAdvisorAndToolResolutionApis() {
        Assertions.assertTrue(classExists("org.springframework.ai.chat.client.advisor.api.Advisor"));
        Assertions.assertTrue(classExists("org.springframework.ai.chat.client.advisor.ToolCallingAdvisor"));
        Assertions.assertTrue(classExists("org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor"));
        Assertions.assertTrue(classExists("org.springframework.ai.tool.resolution.ToolCallbackResolver"));
        Assertions.assertTrue(classExists("org.springframework.ai.tool.resolution.StaticToolCallbackResolver"));
        Assertions.assertTrue(classExists("org.springframework.ai.mcp.SyncMcpToolCallbackProvider"));
    }

    private boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
