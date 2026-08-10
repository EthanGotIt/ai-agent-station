package cn.ethan.core.agent.service;

import cn.ethan.core.agent.enums.AgentMemoryCategoryEnum;
import cn.ethan.core.agent.enums.AgentMemoryOriginEnum;
import cn.ethan.core.agent.enums.AgentMemorySourceEnum;
import cn.ethan.core.agent.enums.RouteTypeEnum;
import cn.ethan.core.agent.model.AgentMemoryCandidateModel;
import cn.ethan.core.agent.model.AgentMemoryEntryModel;
import cn.ethan.core.agent.model.AgentMemoryEvidenceModel;
import cn.ethan.core.agent.model.AgentMemoryExtractionInputModel;
import cn.ethan.core.agent.model.AgentMemoryOptionsModel;
import cn.ethan.core.agent.model.AgentMemorySourceModel;
import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.model.AgentResponseModel;
import cn.ethan.core.agent.port.AgentMemoryStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 记忆服务测试：验证开关、会话隔离、人工优先和 tombstone 规则。
 *
 * @author ethan
 * @date 2026-08-10
 */
class AgentMemoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void generationAndUsageCanBeOverriddenPerRequest() {
        InMemoryStore store = new InMemoryStore();
        AgentMemoryService memories = new AgentMemoryService(false, false, 0.75, store, CLOCK);
        AgentRequestModel request = new AgentRequestModel("request-1", "session-1", "请用中文简洁回复");
        AgentResponseModel response = AgentResponseModel.completed(
                request, RouteTypeEnum.REACT, "react", "好的", 0, 0
        );

        assertTrue(memories.extractionInput(request, "user-1", response).isEmpty());
        AgentRequestModel enabledRequest = new AgentRequestModel(
                "request-2", "session-1", "请用中文简洁回复", new AgentMemoryOptionsModel(true, true)
        );
        AgentMemoryExtractionInputModel input = memories.extractionInput(
                enabledRequest, "user-1", AgentResponseModel.completed(
                        enabledRequest, RouteTypeEnum.REACT, "react", "好的", 0, 0
                )
        ).orElseThrow();
        memories.persistAutomatic(List.of(input), List.of(new AgentMemoryCandidateModel(
                AgentMemoryCategoryEnum.PREFERENCE, "response.language", "中文", 0.90
        )));

        assertEquals(1, memories.forReAct("user-1", "session-1", new AgentMemoryOptionsModel(null, true)).size());
        assertTrue(memories.forReAct("user-1", "session-1", new AgentMemoryOptionsModel(null, false)).isEmpty());
        assertTrue(memories.forReAct("user-1", "session-2", new AgentMemoryOptionsModel(null, true)).isEmpty());
        assertEquals(1, store.evidence.size());
    }

    @Test
    void automaticExtractionNeverOverwritesManualEntryOrTombstone() {
        InMemoryStore store = new InMemoryStore();
        AgentMemoryService memories = new AgentMemoryService(true, true, 0.75, store, CLOCK);
        AgentMemoryEntryModel manual = memories.create(
                "user-1", "session-1", AgentMemoryCategoryEnum.PREFERENCE,
                "response.format", "markdown", null
        );
        AgentMemoryExtractionInputModel input = input("request-1");

        memories.persistAutomatic(List.of(input), List.of(candidate(
                AgentMemoryCategoryEnum.PREFERENCE, "response.format", "plain text", 0.99
        )));
        assertEquals("markdown", store.findOwned(manual.entryId(), "user-1", "session-1").orElseThrow().value());
        assertEquals(AgentMemoryOriginEnum.MANUAL,
                store.findOwned(manual.entryId(), "user-1", "session-1").orElseThrow().origin());

        memories.persistAutomatic(List.of(input), List.of(candidate(
                AgentMemoryCategoryEnum.TASK_CONTEXT, "order.id", "ORDER-001", 0.95
        )));
        AgentMemoryEntryModel automatic = store.findOwnedByKey(
                "user-1", "session-1", "TASK_CONTEXT", "order.id"
        ).orElseThrow();
        memories.delete(automatic.entryId(), "user-1", "session-1", automatic.version());
        memories.persistAutomatic(List.of(input("request-2")), List.of(candidate(
                AgentMemoryCategoryEnum.TASK_CONTEXT, "order.id", "ORDER-002", 0.99
        )));

        AgentMemoryEntryModel tombstone = store.findOwned(automatic.entryId(), "user-1", "session-1").orElseThrow();
        assertTrue(tombstone.deleted());
        assertEquals("ORDER-001", tombstone.value());
        AgentMemoryEntryModel restored = memories.create(
                "user-1", "session-1", AgentMemoryCategoryEnum.TASK_CONTEXT,
                "order.id", "ORDER-003", null
        );
        assertFalse(restored.deleted());
        assertEquals(AgentMemoryOriginEnum.MANUAL, restored.origin());
        assertEquals(automatic.version() + 2, restored.version());
    }

    @Test
    void workflowSuggestionRequiresHighConfidenceAndUsableEntry() {
        InMemoryStore store = new InMemoryStore();
        AgentMemoryService memories = new AgentMemoryService(true, true, 0.75, store, CLOCK);
        store.createEntry(entry("low", "order.id", "ORDER-LOW", 0.89, false, null));
        store.createEntry(entry("expired", "order.id", "ORDER-OLD", 0.99, false, NOW));
        store.createEntry(entry("high", "order.id", "ORDER-001", 0.90, false, NOW.plusSeconds(60)));

        assertEquals("ORDER-001", memories.workflowSuggestion(
                "user-1", "session-1", new AgentMemoryOptionsModel(null, true), "order.id"
        ).orElseThrow().value());
        assertTrue(memories.workflowSuggestion(
                "user-1", "session-1", new AgentMemoryOptionsModel(null, false), "order.id"
        ).isEmpty());
    }

    @Test
    void manualEditAndDeleteRequireCurrentVersion() {
        InMemoryStore store = new InMemoryStore();
        AgentMemoryService memories = new AgentMemoryService(true, true, 0.75, store, CLOCK);
        AgentMemoryEntryModel entry = memories.create(
                "user-1", "session-1", AgentMemoryCategoryEnum.PREFERENCE,
                "response.detail", "standard", null
        );

        AgentMemoryEntryModel updated = memories.edit(
                entry.entryId(), "user-1", "session-1", AgentMemoryCategoryEnum.PREFERENCE,
                "response.detail", "detailed", null, entry.version()
        );
        assertEquals(entry.version() + 1, updated.version());
        assertThrows(cn.ethan.core.agent.exception.AgentMemoryConflictException.class, () ->
                memories.delete(updated.entryId(), "user-1", "session-1", entry.version())
        );
        memories.delete(updated.entryId(), "user-1", "session-1", updated.version());
        assertTrue(store.findOwned(updated.entryId(), "user-1", "session-1").orElseThrow().deleted());
    }

    private static AgentMemoryExtractionInputModel input(String requestId) {
        return new AgentMemoryExtractionInputModel(
                "user-1", "session-1", requestId, AgentMemorySourceEnum.REACT, "问题", "答复"
        );
    }

    private static AgentMemoryCandidateModel candidate(
            AgentMemoryCategoryEnum category, String key, String value, double confidence
    ) {
        return new AgentMemoryCandidateModel(category, key, value, confidence);
    }

    private static AgentMemoryEntryModel entry(
            String entryId, String key, String value, double confidence, boolean deleted, Instant expiresAt
    ) {
        return new AgentMemoryEntryModel(
                entryId, null, "user-1", "session-1", AgentMemoryCategoryEnum.TASK_CONTEXT,
                key, value, AgentMemoryOriginEnum.AUTO, confidence, 0L, deleted, expiresAt, NOW, NOW
        );
    }

    private static final class InMemoryStore implements AgentMemoryStore {

        private final List<AgentMemorySourceModel> sources = new ArrayList<>();
        private final List<AgentMemoryEntryModel> entries = new ArrayList<>();
        private final List<AgentMemoryEvidenceModel> evidence = new ArrayList<>();

        @Override
        public void createSource(AgentMemorySourceModel source) {
            sources.add(source);
        }

        @Override
        public void createEntry(AgentMemoryEntryModel entry) {
            entries.add(entry);
        }

        @Override
        public void appendEvidence(AgentMemoryEvidenceModel item) {
            evidence.add(item);
        }

        @Override
        public List<AgentMemoryEntryModel> list(
                String userId, String sessionId, boolean includeDeleted, int limit
        ) {
            return entries.stream()
                    .filter(entry -> entry.userId().equals(userId) && entry.sessionId().equals(sessionId))
                    .filter(entry -> includeDeleted || !entry.deleted())
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<AgentMemoryEntryModel> findOwned(String entryId, String userId, String sessionId) {
            return entries.stream().filter(entry -> entry.entryId().equals(entryId)
                    && entry.userId().equals(userId) && entry.sessionId().equals(sessionId)).findFirst();
        }

        @Override
        public Optional<AgentMemoryEntryModel> findOwnedByKey(
                String userId, String sessionId, String category, String memoryKey
        ) {
            return entries.stream().filter(entry -> entry.userId().equals(userId)
                    && entry.sessionId().equals(sessionId) && entry.category().name().equals(category)
                    && entry.memoryKey().equals(memoryKey)).findFirst();
        }

        @Override
        public List<AgentMemoryEvidenceModel> listEvidence(String entryId, String userId, String sessionId) {
            return findOwned(entryId, userId, sessionId).isEmpty() ? List.of()
                    : evidence.stream().filter(item -> item.entryId().equals(entryId)).toList();
        }

        @Override
        public boolean update(AgentMemoryEntryModel expected, AgentMemoryEntryModel updated) {
            int index = entries.indexOf(expected);
            if (index < 0) {
                return false;
            }
            entries.set(index, updated);
            return true;
        }
    }
}
