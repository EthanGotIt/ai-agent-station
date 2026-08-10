package cn.ethan.infrastructure.agentscope.tool;

import cn.ethan.core.agent.enums.AgentMemoryCategoryEnum;
import cn.ethan.core.agent.model.AgentMemoryEntryModel;
import cn.ethan.core.agent.model.AgentMemoryEvidenceModel;
import cn.ethan.core.agent.model.AgentMemorySourceModel;
import cn.ethan.core.agent.port.AgentMemoryStore;
import cn.ethan.core.agent.service.AgentMemoryService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话偏好 ASK 工具测试：确认前仅请求授权，实际调用才写入当前会话记忆。
 *
 * @author ethan
 * @date 2026-08-10
 */
class SaveSessionPreferenceToolTest {

    @Test
    void asksBeforeWritingNormalizedCurrentSessionPreference() {
        InMemoryStore store = new InMemoryStore();
        SaveSessionPreferenceTool tool = new SaveSessionPreferenceTool(new AgentMemoryService(
                false, true, 0.75, store, Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC)
        ));
        Map<String, Object> input = Map.of("key", "response.format", "value", "markdown");

        assertEquals(PermissionBehavior.ASK, tool.checkPermissions(input, null).block().getBehavior());
        assertFalse(tool.isReadOnly());
        assertTrue(tool.isConcurrencySafe());
        assertTrue(store.entries.isEmpty());

        ToolResultBlock result = tool.callAsync(parameter(input)).block();
        TextBlock content = (TextBlock) result.getOutput().get(0);

        assertTrue(content.getText().contains("PREFERENCE_SAVED"));
        AgentMemoryEntryModel entry = store.entries.get(0);
        assertEquals("user-1", entry.userId());
        assertEquals("session-1", entry.sessionId());
        assertEquals(AgentMemoryCategoryEnum.PREFERENCE, entry.category());
        assertEquals("markdown", entry.value());
    }

    private ToolCallParam parameter(Map<String, Object> input) {
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id("tool-call-1")
                        .name(SaveSessionPreferenceTool.NAME).input(input).build())
                .input(input)
                .runtimeContext(RuntimeContext.builder().userId("user-1").sessionId("session-1").build())
                .build();
    }

    private static final class InMemoryStore implements AgentMemoryStore {

        private final List<AgentMemoryEntryModel> entries = new ArrayList<>();

        @Override
        public void createSource(AgentMemorySourceModel source) {
        }

        @Override
        public void createEntry(AgentMemoryEntryModel entry) {
            entries.add(entry);
        }

        @Override
        public void appendEvidence(AgentMemoryEvidenceModel evidence) {
        }

        @Override
        public List<AgentMemoryEntryModel> list(String userId, String sessionId, boolean includeDeleted, int limit) {
            return entries.stream().filter(entry -> entry.userId().equals(userId) && entry.sessionId().equals(sessionId))
                    .filter(entry -> includeDeleted || !entry.deleted()).limit(limit).toList();
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
            return List.of();
        }

        @Override
        public boolean update(AgentMemoryEntryModel expected, AgentMemoryEntryModel updated) {
            int index = entries.indexOf(expected);
            if (index < 0) return false;
            entries.set(index, updated);
            return true;
        }
    }
}
