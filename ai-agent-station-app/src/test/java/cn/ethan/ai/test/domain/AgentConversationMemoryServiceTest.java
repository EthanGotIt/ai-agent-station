package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentConversationMemoryRepository;
import cn.ethan.ai.domain.agent.model.valobj.AgentConversationMessageVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentConversationSessionVO;
import cn.ethan.ai.domain.agent.model.valobj.SessionContextSnapshotVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentConversationMessageRoleEnumVO;
import cn.ethan.ai.domain.agent.service.execute.runtime.AgentConversationMemoryService;
import cn.ethan.ai.domain.agent.service.execute.runtime.SessionContextAssembler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AgentConversationMemoryServiceTest {

    @Test
    public void shouldPersistUserAndAssistantAsOneCompleteTurn() {
        InMemoryRepository repository = new InMemoryRepository();
        AgentConversationMemoryService service = service(repository);
        complete(service, "session-a", "run-1", "用户问题", "最终回答");

        Assertions.assertEquals(2, repository.messages.size());
        Assertions.assertTrue(service.loadSessionContext("session-a").getContextSummary().contains("最终回答"));
    }

    @Test
    public void shouldExcludeFailedRunOrphanUserMessage() {
        InMemoryRepository repository = new InMemoryRepository();
        AgentConversationMemoryService service = service(repository);
        service.recordUserMessage("session-a", "failed-run", "不应注入的问题");

        Assertions.assertFalse(service.loadSessionContext("session-a").getContextSummary().contains("不应注入"));
    }

    @Test
    public void shouldIsolateSessions() {
        InMemoryRepository repository = new InMemoryRepository();
        AgentConversationMemoryService service = service(repository);
        complete(service, "session-a", "run-a", "A问题", "A回答");
        complete(service, "session-b", "run-b", "B问题", "B回答");

        String context = service.loadSessionContext("session-a").getContextSummary();
        Assertions.assertTrue(context.contains("A回答"));
        Assertions.assertFalse(context.contains("B回答"));
    }

    @Test
    public void shouldMergeExplicitPreferenceAfterSuccessfulTurn() {
        InMemoryRepository repository = new InMemoryRepository();
        AgentConversationMemoryService service = service(repository);
        complete(service, "session-a", "run-1", "后续请用中文简洁列表回答", "已确认");

        String json = repository.session.getSummaryJson();
        Assertions.assertTrue(json.contains("zh-CN"));
        Assertions.assertTrue(json.contains("concise"));
        Assertions.assertTrue(json.contains("list"));
    }

    @Test
    public void shouldRollOlderTurnsIntoStructuredSummary() {
        InMemoryRepository repository = new InMemoryRepository();
        AgentConversationMemoryService service = service(repository);
        for (int i = 1; i <= 7; i++) {
            complete(service, "session-a", "run-" + i, "必须完成目标" + i + "内容".repeat(220), "回答" + i);
        }

        Assertions.assertTrue(repository.session.getSummarizedMessageId() > 0);
        Assertions.assertTrue(repository.session.getSummaryJson().contains("goals"));
        Assertions.assertFalse(repository.session.getSummaryJson().contains("回答1"));
    }

    @Test
    public void shouldRecoverAcrossServiceInstances() {
        InMemoryRepository repository = new InMemoryRepository();
        complete(service(repository), "session-a", "run-1", "跨重启问题", "跨重启回答");

        SessionContextSnapshotVO snapshot = service(repository).loadSessionContext("session-a");

        Assertions.assertTrue(snapshot.getContextSummary().contains("跨重启回答"));
    }

    @Test
    public void shouldRetryOneOptimisticLockConflict() {
        InMemoryRepository repository = new InMemoryRepository();
        complete(service(repository), "session-a", "run-1", "问题1", "回答1");
        repository.failNextUpdate = true;

        complete(service(repository), "session-a", "run-2", "问题2", "回答2");

        Assertions.assertTrue(repository.updateAttempts >= 2);
        Assertions.assertNotNull(repository.session.getExpiresAt());
    }

    @Test
    public void shouldClearMessagesAndSummaryTogether() {
        InMemoryRepository repository = new InMemoryRepository();
        AgentConversationMemoryService service = service(repository);
        complete(service, "session-a", "run-1", "问题", "回答");

        service.clearSessionMemory("session-a");

        Assertions.assertTrue(repository.messages.isEmpty());
        Assertions.assertNull(repository.session);
    }

    @Test
    public void shouldCleanupExpiredSession() {
        InMemoryRepository repository = new InMemoryRepository();
        complete(service(repository), "session-a", "run-1", "问题", "回答");
        repository.session.setExpiresAt(LocalDateTime.now().minusDays(1));

        service(repository).cleanupExpiredMemory();

        Assertions.assertNull(repository.session);
        Assertions.assertTrue(repository.messages.isEmpty());
    }

    @Test
    public void shouldIgnoreBlankSession() {
        InMemoryRepository repository = new InMemoryRepository();
        AgentConversationMemoryService service = service(repository);
        service.recordUserMessage(" ", "run-1", "匿名请求");

        Assertions.assertTrue(repository.messages.isEmpty());
        Assertions.assertEquals("", service.loadSessionContext(" ").getContextSummary());
    }

    private AgentConversationMemoryService service(InMemoryRepository repository) {
        return new AgentConversationMemoryService(repository, new SessionContextAssembler());
    }

    private void complete(AgentConversationMemoryService service,
                          String sessionId,
                          String runId,
                          String user,
                          String assistant) {
        service.recordUserMessage(sessionId, runId, user);
        service.recordAssistantMessage(sessionId, runId, assistant);
    }

    private static class InMemoryRepository implements IAgentConversationMemoryRepository {

        private final List<AgentConversationMessageVO> messages = new ArrayList<>();
        private AgentConversationSessionVO session;
        private long sequence;
        private boolean failNextUpdate;
        private int updateAttempts;

        @Override
        public void save(AgentConversationMessageVO message) {
            message.setId(++sequence);
            messages.add(message);
        }

        @Override
        public List<AgentConversationMessageVO> queryCompleteTurnMessages(String sessionId, long afterMessageId, int limit) {
            List<AgentConversationMessageVO> complete = messages.stream()
                    .filter(message -> sessionId.equals(message.getSessionId()) && message.getId() > afterMessageId)
                    .filter(message -> messages.stream().anyMatch(candidate -> candidate.getRunId().equals(message.getRunId())
                            && candidate.getRole() == AgentConversationMessageRoleEnumVO.USER))
                    .filter(message -> messages.stream().anyMatch(candidate -> candidate.getRunId().equals(message.getRunId())
                            && candidate.getRole() == AgentConversationMessageRoleEnumVO.ASSISTANT))
                    .sorted(Comparator.comparing(AgentConversationMessageVO::getId))
                    .toList();
            return complete.subList(Math.max(0, complete.size() - limit), complete.size());
        }

        @Override
        public AgentConversationSessionVO querySession(String sessionId) {
            return session == null || !sessionId.equals(session.getSessionId()) ? null : copy(session);
        }

        @Override
        public boolean createSession(AgentConversationSessionVO value) {
            if (session != null) return false;
            session = copy(value);
            return true;
        }

        @Override
        public boolean updateSession(AgentConversationSessionVO value, int expectedVersion) {
            updateAttempts++;
            if (failNextUpdate) {
                failNextUpdate = false;
                return false;
            }
            if (session == null || session.getVersion() != expectedVersion) return false;
            session = copy(value);
            return true;
        }

        @Override
        public void deleteSessionMemory(String sessionId) {
            messages.removeIf(message -> sessionId.equals(message.getSessionId()));
            if (session != null && sessionId.equals(session.getSessionId())) session = null;
        }

        @Override
        public int deleteExpired(LocalDateTime now) {
            if (session != null && session.getExpiresAt().isBefore(now)) {
                String sessionId = session.getSessionId();
                deleteSessionMemory(sessionId);
                return 1;
            }
            return 0;
        }

        private AgentConversationSessionVO copy(AgentConversationSessionVO value) {
            return AgentConversationSessionVO.builder()
                    .sessionId(value.getSessionId()).summaryJson(value.getSummaryJson())
                    .summarizedMessageId(value.getSummarizedMessageId()).version(value.getVersion())
                    .expiresAt(value.getExpiresAt()).updateTime(value.getUpdateTime()).build();
        }
    }
}
