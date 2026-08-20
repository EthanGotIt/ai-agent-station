package cn.ethan.core.agent.context;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文组装测试：验证长历史触发快照并保留受预算约束的最近窗口。
 *
 * @author ethan
 * @date 2026-08-20
 */
class AgentContextAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void createsSnapshotAndKeepsRecentItemsWithinBudget() {
        List<AgentItemModel> history = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            history.add(new AgentItemModel(
                    "item-" + index,
                    "thread-1",
                    "turn-" + index,
                    index + 1L,
                    AgentItemTypeEnum.USER_MESSAGE,
                    "x".repeat(220),
                    NOW
            ));
        }
        RecordingItems items = new RecordingItems(history);
        RecordingSnapshots snapshots = new RecordingSnapshots();
        AgentContextAssembler assembler = new AgentContextAssembler(
                items,
                snapshots,
                Clock.fixed(NOW, ZoneOffset.UTC),
                1_000,
                500,
                80,
                128
        );

        List<AgentItemModel> result = assembler.assemble(thread());

        assertFalse(result.isEmpty());
        assertEquals(AgentItemTypeEnum.EXECUTION_EVENT, result.get(0).type());
        assertTrue(result.get(0).payload().contains("历史摘要"));
        assertEquals(1, snapshots.saved.size());
        assertEquals(1L, snapshots.saved.get(0).version());
        assertTrue(result.stream().mapToInt(item -> item.payload().length() / 2 + 1).sum() <= 872);
    }

    @Test
    void resumesAfterLatestSnapshotAndExcludesCurrentTurn() {
        List<AgentItemModel> history = List.of(
                new AgentItemModel("old", "thread-1", "turn-old", 11,
                        AgentItemTypeEnum.USER_MESSAGE, "old", NOW),
                new AgentItemModel("current", "thread-1", "turn-current", 12,
                        AgentItemTypeEnum.USER_MESSAGE, "current", NOW),
                new AgentItemModel("next", "thread-1", "turn-next", 13,
                        AgentItemTypeEnum.USER_MESSAGE, "next", NOW)
        );
        RecordingItems items = new RecordingItems(history);
        RecordingSnapshots snapshots = new RecordingSnapshots();
        snapshots.saved.add(new AgentContextSnapshotModel(
                "snapshot-1", "thread-1", 10, 1, 4, "MODEL_SAFE_V1\nsummary", NOW));
        AgentContextAssembler assembler = new AgentContextAssembler(
                items, snapshots, Clock.fixed(NOW, ZoneOffset.UTC), 2_000, 1_500, 256, 128);

        List<AgentItemModel> result = assembler.assemble(thread(), "turn-current");

        assertEquals(10L, items.lastAfterSequence);
        assertTrue(result.stream().noneMatch(item -> "turn-current".equals(item.turnId())));
        assertTrue(result.stream().anyMatch(item -> item.payload().contains("summary")));
    }

    @Test
    void truncatesLargeToolResultBeforeModelContext() {
        RecordingItems items = new RecordingItems(List.of(
                new AgentItemModel("tool", "thread-1", "turn-tool", 1,
                        AgentItemTypeEnum.TOOL_RESULT, "r".repeat(400), NOW)
        ));
        AgentContextAssembler assembler = new AgentContextAssembler(
                items, new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC), 2_000, 1_500, 80, 128);

        List<AgentItemModel> result = assembler.assemble(thread());

        assertTrue(result.stream().anyMatch(item -> item.type() == AgentItemTypeEnum.TOOL_RESULT
                && item.payload().contains("\"truncated\":true")));
    }

    @Test
    void excludesWorkflowAnswersAndInternalControlItemsFromModelContext() {
        String secret = "apiKey=secret-like-answer";
        RecordingItems items = new RecordingItems(List.of(
                new AgentItemModel("answer", "thread-1", "turn-answer", 1,
                        AgentItemTypeEnum.WORKFLOW_ANSWER, secret, NOW),
                new AgentItemModel("state", "thread-1", "turn-answer", 2,
                        AgentItemTypeEnum.TURN_STATE, secret, NOW),
                new AgentItemModel("recovery", "thread-1", "turn-answer", 3,
                        AgentItemTypeEnum.EXECUTION_EVENT, secret, NOW),
                new AgentItemModel("workflow-result", "thread-1", "turn-answer", 4,
                        AgentItemTypeEnum.WORKFLOW_RESULT, "已拒绝退款", NOW)
        ));
        RecordingSnapshots snapshots = new RecordingSnapshots();
        snapshots.saved.add(new AgentContextSnapshotModel(
                "legacy-unsafe", "thread-1", 3, 1, 20, "apiKey=secret-like-answer", NOW));
        AgentContextAssembler assembler = new AgentContextAssembler(
                items, snapshots, Clock.fixed(NOW, ZoneOffset.UTC), 2_000, 1_500, 256, 128);

        List<AgentItemModel> result = assembler.assemble(thread());

        assertTrue(result.stream().noneMatch(item -> item.payload().contains(secret)));
        assertEquals(List.of(AgentItemTypeEnum.WORKFLOW_RESULT),
                result.stream().map(AgentItemModel::type).toList());
    }

    private AgentThreadModel thread() {
        return new AgentThreadModel(
                "thread-1", "user-1", "测试 Thread", AgentThreadStatusEnum.ACTIVE,
                null, null, 7, NOW, NOW
        );
    }

    private static final class RecordingItems implements AgentItemStore {
        private final List<AgentItemModel> history;
        private long lastAfterSequence;

        private RecordingItems(List<AgentItemModel> history) {
            this.history = history;
        }

        @Override
        public long appendItem(AgentItemModel item) {
            throw new UnsupportedOperationException("test does not append items");
        }

        @Override
        public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
            lastAfterSequence = afterSequence;
            return history.stream().filter(item -> item.sequence() > afterSequence).limit(limit).toList();
        }
    }

    private static final class RecordingSnapshots implements AgentContextSnapshotStore {
        private final List<AgentContextSnapshotModel> saved = new ArrayList<>();

        @Override
        public Optional<AgentContextSnapshotModel> findLatestSnapshot(String userId, String threadId) {
            return saved.isEmpty() ? Optional.empty() : Optional.of(saved.get(saved.size() - 1));
        }

        @Override
        public void saveSnapshot(AgentContextSnapshotModel snapshot) {
            saved.add(snapshot);
        }
    }
}
