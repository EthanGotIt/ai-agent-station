package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentConversationMemoryRepository;
import cn.ethan.ai.domain.agent.model.valobj.AgentConversationMessageVO;
import cn.ethan.ai.domain.agent.model.valobj.SessionContextSnapshotVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentConversationMessageRoleEnumVO;
import cn.ethan.ai.domain.agent.service.execute.flow.AgentConversationMemoryService;
import cn.ethan.ai.domain.agent.service.execute.flow.SessionContextAssembler;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class AgentConversationMemoryServiceTest {

    @Test
    public void shouldPersistOnlyExplicitUserAndAssistantMessages() {
        InMemoryRepository repository = new InMemoryRepository();
        AgentConversationMemoryService service = new AgentConversationMemoryService(repository, new SessionContextAssembler());

        service.recordUserMessage("session-a", "run-1", "用户问题");
        service.recordAssistantMessage("session-a", "run-1", "最终回答");

        Assert.assertEquals(2, repository.messages.size());
        Assert.assertEquals(AgentConversationMessageRoleEnumVO.USER, repository.messages.get(0).getRole());
        Assert.assertEquals(AgentConversationMessageRoleEnumVO.ASSISTANT, repository.messages.get(1).getRole());
    }

    @Test
    public void shouldIsolateLoadedContextBySession() {
        InMemoryRepository repository = new InMemoryRepository();
        AgentConversationMemoryService service = new AgentConversationMemoryService(repository, new SessionContextAssembler());
        service.recordUserMessage("session-a", "run-1", "session A 的问题");
        service.recordAssistantMessage("session-a", "run-1", "session A 的回答");
        service.recordUserMessage("session-b", "run-2", "session B 的问题");

        SessionContextSnapshotVO sessionA = service.loadSessionContext("session-a");

        Assert.assertTrue(sessionA.getContextSummary().contains("session A 的回答"));
        Assert.assertFalse(sessionA.getContextSummary().contains("session B 的问题"));
    }

    @Test
    public void shouldSkipPersistenceAndLoadingWhenSessionIdIsBlank() {
        InMemoryRepository repository = new InMemoryRepository();
        AgentConversationMemoryService service = new AgentConversationMemoryService(repository, new SessionContextAssembler());

        service.recordUserMessage(" ", "run-1", "匿名请求");
        SessionContextSnapshotVO snapshot = service.loadSessionContext(" ");

        Assert.assertTrue(repository.messages.isEmpty());
        Assert.assertEquals("", snapshot.getContextSummary());
    }

    private static class InMemoryRepository implements IAgentConversationMemoryRepository {

        private final List<AgentConversationMessageVO> messages = new ArrayList<>();

        @Override
        public void save(AgentConversationMessageVO message) {
            messages.add(message);
        }

        @Override
        public List<AgentConversationMessageVO> queryRecentMessages(String sessionId, int limit) {
            return messages.stream()
                    .filter(message -> sessionId.equals(message.getSessionId()))
                    .skip(Math.max(0, messages.stream().filter(message -> sessionId.equals(message.getSessionId())).count() - limit))
                    .toList();
        }
    }

}
