package cn.ethan.core.agent.context;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.execution.AgentExecutionCancelledException;
import cn.ethan.core.agent.execution.AgentExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文组装测试：验证固定水位、完整历史和受控预算边界。
 *
 * @author ethan
 * @date 2026-08-20
 */
class AgentContextAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void readsCompleteHistoryWithoutCreatingLegacySnapshot() {
        List<AgentItemModel> history = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            history.add(new AgentItemModel(
                    "item-" + index,
                    "thread-1",
                    "turn-" + index,
                    index + 1L,
                    AgentItemTypeEnum.USER_MESSAGE,
                    "x".repeat(120),
                    NOW
            ));
        }
        history.add(new AgentItemModel(
                "terminal", "thread-1", "turn-5", 7,
                AgentItemTypeEnum.TURN_STATE, "{\"status\":\"COMPLETED\"}", NOW
        ));
        RecordingItems items = new RecordingItems(history);
        RecordingSnapshots snapshots = new RecordingSnapshots();
        AgentContextAssembler assembler = new AgentContextAssembler(
                items,
                snapshots,
                Clock.fixed(NOW, ZoneOffset.UTC),
                1_000,
                300,
                80,
                128
        );

        List<AgentItemModel> result = assembler.assembleWithReport(thread(), null, "近期订单状态").items();

        assertFalse(result.isEmpty());
        assertEquals(AgentItemTypeEnum.USER_MESSAGE, result.get(0).type());
        assertTrue(result.stream().anyMatch(item -> item.payload().contains("x".repeat(120))));
        assertTrue(snapshots.saved.isEmpty());
        assertEquals(7L, assembler.assembleWithReport(thread(), null, "近期订单状态")
                .report().readWatermark());
    }

    @Test
    void ignoresLegacySnapshotAndExcludesCurrentTurn() {
        List<AgentItemModel> history = List.of(
                new AgentItemModel("old", "thread-1", "turn-old", 1,
                        AgentItemTypeEnum.USER_MESSAGE, "old", NOW),
                new AgentItemModel("current", "thread-1", "turn-current", 2,
                        AgentItemTypeEnum.USER_MESSAGE, "current", NOW),
                new AgentItemModel("next", "thread-1", "turn-next", 3,
                        AgentItemTypeEnum.USER_MESSAGE, "next", NOW)
        );
        RecordingItems items = new RecordingItems(history);
        RecordingSnapshots snapshots = new RecordingSnapshots();
        snapshots.saved.add(new AgentContextSnapshotModel(
                "snapshot-1", "thread-1", 10, 1, 4, "MODEL_SAFE_V1\nsummary", NOW));
        AgentContextAssembler assembler = new AgentContextAssembler(
                items, snapshots, Clock.fixed(NOW, ZoneOffset.UTC), 2_000, 1_500, 256, 128);

        List<AgentItemModel> result = assembler.assembleWithReport(thread(), "turn-current", "继续查询").items();

        assertEquals(0L, items.lastAfterSequence);
        assertTrue(result.stream().noneMatch(item -> "turn-current".equals(item.turnId())));
        assertTrue(result.stream().anyMatch(item -> item.payload().contains("old")));
        assertTrue(result.stream().anyMatch(item -> item.payload().contains("next")));
    }

    @Test
    void truncatesLargeToolResultBeforeModelContext() {
        RecordingItems items = new RecordingItems(List.of(
                new AgentItemModel("tool", "thread-1", "turn-tool", 1,
                        AgentItemTypeEnum.TOOL_RESULT, "r".repeat(400), NOW)
        ));
        AgentContextAssembler assembler = new AgentContextAssembler(
                items, new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC), 2_000, 1_500, 80, 128);

        List<AgentItemModel> result = assembler.assembleWithReport(thread(), null, "查询订单").items();

        assertTrue(result.stream().anyMatch(item -> item.type() == AgentItemTypeEnum.TOOL_RESULT
                && item.payload().contains("\"truncated\":true")));
    }

    @Test
    void boundsAlreadyEnvelopedToolResultWithoutNestingTheOldJson() {
        String oversized = "{\"tool\":\"lookup_order\",\"invocationId\":\"inv-1\","
                + "\"status\":\"SUCCESS\",\"result\":\"" + "x".repeat(400) + "\",\"truncated\":false}";
        RecordingItems items = new RecordingItems(List.of(
                new AgentItemModel("tool", "thread-1", "turn-tool", 1,
                        AgentItemTypeEnum.TOOL_RESULT, oversized, NOW)
        ));
        AgentContextAssembler assembler = new AgentContextAssembler(
                items, new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC), 2_000, 1_500, 256, 128);

        AgentItemModel bounded = assembler.assembleWithReport(thread(), null, "查询订单").items().get(0);

        assertTrue(bounded.payloadJson().length() <= 256);
        assertTrue(bounded.payload().contains("\"tool\":\"lookup_order\""));
        assertTrue(bounded.payload().contains("\"invocationId\":\"inv-1\""));
        assertTrue(bounded.payload().contains("\"truncated\":true"));
        assertFalse(bounded.payload().contains("\"result\":\"{\\\"tool\""));
    }

    @Test
    void retainsItemsWhenHistoryExceedsInputBudget() {
        RecordingItems items = new RecordingItems(List.of(
                new AgentItemModel("large-1", "thread-1", "turn-1", 1,
                        AgentItemTypeEnum.USER_MESSAGE, "x".repeat(2_200), NOW),
                new AgentItemModel("large-2", "thread-1", "turn-2", 2,
                        AgentItemTypeEnum.USER_MESSAGE, "y".repeat(2_200), NOW)
        ));
        AgentContextAssembler assembler = new AgentContextAssembler(
                items, new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC), 1_000, 900, 256, 128);

        AgentContextAssembly result = assembler.assembleWithReport(thread(), null, "当前请求");

        assertFalse(result.items().isEmpty());
        assertTrue(result.report().degraded());
        assertTrue(result.report().estimatedTokens() > result.report().inputBudget());
        assertEquals(2, result.report().readItemCount());
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

        List<AgentItemModel> result = assembler.assembleWithReport(thread(), null, "继续").items();

        assertTrue(result.stream().noneMatch(item -> item.payload().contains(secret)));
        assertEquals(List.of(AgentItemTypeEnum.WORKFLOW_RESULT),
                result.stream().map(AgentItemModel::type).toList());
    }

    @Test
    void readsCompleteHistoryInsteadOfLatestWindow() {
        List<AgentItemModel> history = new ArrayList<>();
        for (int sequence = 1; sequence <= 350; sequence++) {
            history.add(new AgentItemModel(
                    "item-" + sequence, "thread-1", "turn-" + sequence, sequence,
                    AgentItemTypeEnum.USER_MESSAGE, "message-" + sequence, NOW
            ));
        }
        AgentContextAssembler assembler = new AgentContextAssembler(
                new RecordingItems(history), new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC),
                100_000, 90_000, 256, 128);

        List<AgentItemModel> result = assembler.assembleWithReport(thread(), null, "最新问题").items();

        assertEquals(350, result.size());
        assertEquals(1L, result.get(0).sequence());
        assertEquals(350L, result.get(result.size() - 1).sequence());
    }

    @Test
    void keepsTheCapturedWatermarkWhenItemsAreAppendedDuringPaging() {
        List<AgentItemModel> history = new ArrayList<>();
        for (int sequence = 1; sequence <= 301; sequence++) {
            history.add(new AgentItemModel("item-" + sequence, "thread-1", "turn-" + sequence,
                    sequence, AgentItemTypeEnum.USER_MESSAGE, "事实-" + sequence, NOW));
        }
        AtomicBoolean appended = new AtomicBoolean();
        AgentItemStore store = new RecordingItems(history) {
            @Override
            public long captureWatermark(String userId, String threadId) {
                return 300L;
            }

            @Override
            public List<AgentItemModel> listItemsThrough(
                    String userId, String threadId, long afterSequence, long throughSequence, int limit
            ) {
                if (appended.compareAndSet(false, true)) {
                    history.add(new AgentItemModel("item-302", "thread-1", "turn-302", 302,
                            AgentItemTypeEnum.USER_MESSAGE, "水位之后新增", NOW));
                }
                return history.stream().filter(item -> item.sequence() > afterSequence
                        && item.sequence() <= throughSequence).limit(limit).toList();
            }
        };

        AgentContextAssembly assembly = new AgentContextAssembler(
                store, new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC),
                100_000, 90_000, 256, 128).assembleWithReport(thread(), null, "继续");

        assertEquals(300, assembly.report().readItemCount());
        assertTrue(assembly.items().stream().noneMatch(item -> item.sequence() > 300));
        assertTrue(history.stream().anyMatch(item -> item.payload().contains("水位之后新增")));
    }

    @Test
    void firstStageReadsRawItemsEvenWhenLegacySnapshotExists() {
        List<AgentItemModel> history = List.of(
                new AgentItemModel("old", "thread-1", "turn-old", 1,
                        AgentItemTypeEnum.USER_MESSAGE, "原始事实", NOW),
                new AgentItemModel("latest", "thread-1", "turn-latest", 2,
                        AgentItemTypeEnum.USER_MESSAGE, "最新事实", NOW)
        );
        RecordingSnapshots snapshots = new RecordingSnapshots();
        snapshots.saved.add(new AgentContextSnapshotModel(
                "legacy", "thread-1", 1, 1, 2, "MODEL_SAFE_V1\n不应跳过原始事实", NOW));
        AgentContextAssembler assembler = new AgentContextAssembler(
                new RecordingItems(history), snapshots, Clock.fixed(NOW, ZoneOffset.UTC),
                2_000, 256, 128, null,
                new AgentContextCompactionSettings(false, 0.80, 0.16,
                        2_048, 8_000, 4_096, 1_024, 1));

        List<AgentItemModel> result = assembler.assembleWithReport(thread(), null, "继续").items();

        assertTrue(result.stream().anyMatch(item -> item.payload().contains("原始事实")));
        assertTrue(result.stream().anyMatch(item -> item.payload().contains("最新事实")));
        assertTrue(result.stream().noneMatch(item -> item.payload().contains("不应跳过")));
        assertEquals(1L, result.get(0).sequence());
    }

    @Test
    void ignoresSummaryFailureAndDoesNotPersistLegacySnapshot() {
        List<AgentItemModel> history = new ArrayList<>();
        for (int sequence = 1; sequence <= 4; sequence++) {
            history.add(new AgentItemModel(
                    "item-" + sequence, "thread-1", "turn-" + sequence, sequence,
                    AgentItemTypeEnum.USER_MESSAGE, "x".repeat(220), NOW
            ));
        }
        history.add(new AgentItemModel("terminal", "thread-1", "turn-4", 5,
                AgentItemTypeEnum.TURN_STATE, "{\"status\":\"COMPLETED\"}", NOW));
        RecordingSnapshots snapshots = new RecordingSnapshots();
        AgentContextAssembler assembler = new AgentContextAssembler(
                new RecordingItems(history), snapshots, Clock.fixed(NOW, ZoneOffset.UTC),
                1_000, 256, 128, (request, context) -> {
                    throw new IllegalStateException("summary unavailable");
                }, AgentContextCompactionSettings.defaults());

        AgentContextAssembly result = assembler.assembleWithReport(thread(), null, "当前问题");

        assertFalse(result.report().degraded());
        assertTrue(result.report().estimatedTokens() <= result.report().inputBudget());
        assertTrue(snapshots.saved.isEmpty());
    }

    @Test
    void rejectsHistoryPageThatDoesNotAdvanceStrictly() {
        AgentItemModel first = new AgentItemModel("first", "thread-1", "turn-1", 1,
                AgentItemTypeEnum.USER_MESSAGE, "first", NOW);
        AgentItemStore invalid = new RecordingItems(List.of(first)) {
            @Override
            public long captureWatermark(String userId, String threadId) {
                return 2L;
            }

            @Override
            public List<AgentItemModel> listItemsThrough(
                    String userId, String threadId, long afterSequence, long throughSequence, int limit
            ) {
                return List.of(first, first);
            }
        };

        AgentContextHistoryException failure = assertThrows(AgentContextHistoryException.class,
                () -> new AgentContextAssembler(invalid, new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC),
                        2_000, 1_500, 256, 128).assembleWithReport(thread(), null, "继续"));

        assertEquals("CONTEXT_HISTORY_INVALID", failure.code());
    }

    @Test
    void wrapsSnapshotChainReadFailureAsHistoryError() {
        List<AgentItemModel> history = List.of(
                new AgentItemModel("first", "thread-1", "turn-1", 1,
                        AgentItemTypeEnum.USER_MESSAGE, "first", NOW),
                new AgentItemModel("second", "thread-1", "turn-2", 2,
                        AgentItemTypeEnum.USER_MESSAGE, "second", NOW)
        );
        RecordingSnapshots snapshots = new RecordingSnapshots();
        snapshots.saved.add(new AgentContextSnapshotModel(
                "latest", "thread-1", 2, 2,
                AgentContextTokenEstimator.estimateText("valid summary"), "valid summary", NOW,
                2, "base", 2, 1, "context-summary-v2", 2_048));
        snapshots.findSnapshotFailure = new IllegalStateException("snapshot backend unavailable");

        AgentContextHistoryException failure = assertThrows(AgentContextHistoryException.class,
                () -> new AgentContextAssembler(new RecordingItems(history), snapshots,
                        Clock.fixed(NOW, ZoneOffset.UTC), 2_000, 1_500, 256, 128)
                        .assembleWithReport(thread(), null, "继续"));

        assertEquals("CONTEXT_HISTORY_INVALID", failure.code());
        assertEquals("snapshot backend unavailable", failure.getCause().getMessage());
    }

    @Test
    void rejectsHistoryThatEndsBeforeTheCapturedWatermark() {
        AgentItemStore invalid = new RecordingItems(List.of(
                new AgentItemModel("first", "thread-1", "turn-1", 1,
                        AgentItemTypeEnum.USER_MESSAGE, "first", NOW)
        )) {
            @Override
            public long captureWatermark(String userId, String threadId) {
                return 2L;
            }

            @Override
            public List<AgentItemModel> listItemsThrough(
                    String userId, String threadId, long afterSequence, long throughSequence, int limit
            ) {
                return afterSequence == 0L ? super.listItemsThrough(userId, threadId,
                        afterSequence, throughSequence, limit) : List.of();
            }
        };

        AgentContextHistoryException failure = assertThrows(AgentContextHistoryException.class,
                () -> new AgentContextAssembler(invalid, new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC),
                        2_000, 1_500, 256, 128).assembleWithReport(thread(), null, "继续"));

        assertEquals("CONTEXT_HISTORY_INVALID", failure.code());
    }

    @Test
    void rejectsHistoryWithASequenceGapInsideTheCapturedRange() {
        List<AgentItemModel> history = new ArrayList<>();
        history.add(new AgentItemModel("first", "thread-1", "turn-1", 1,
                AgentItemTypeEnum.USER_MESSAGE, "first", NOW));
        history.add(new AgentItemModel("third", "thread-1", "turn-3", 3,
                AgentItemTypeEnum.USER_MESSAGE, "third", NOW));
        AgentItemStore invalid = new RecordingItems(history) {
            @Override
            public long captureWatermark(String userId, String threadId) {
                return 3L;
            }
        };

        AgentContextHistoryException failure = assertThrows(AgentContextHistoryException.class,
                () -> new AgentContextAssembler(invalid, new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC),
                        2_000, 1_500, 256, 128).assembleWithReport(thread(), null, "继续"));

        assertEquals("CONTEXT_HISTORY_INVALID", failure.code());
    }

    @Test
    void rejectsHistoryThatStartsAfterTheCapturedCursor() {
        AgentItemModel second = new AgentItemModel("second", "thread-1", "turn-2", 2,
                AgentItemTypeEnum.USER_MESSAGE, "second", NOW);
        AgentItemStore invalid = new RecordingItems(List.of(second)) {
            @Override
            public long captureWatermark(String userId, String threadId) {
                return 2L;
            }
        };

        AgentContextHistoryException failure = assertThrows(AgentContextHistoryException.class,
                () -> new AgentContextAssembler(invalid, new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC),
                        2_000, 1_500, 256, 128).assembleWithReport(thread(), null, "继续"));

        assertEquals("CONTEXT_HISTORY_INVALID", failure.code());
    }

    @Test
    void retainsHistoryInTheAssemblyWhenTheHardBudgetCannotFit() {
        AgentItemModel large = new AgentItemModel("large", "thread-1", "turn-1", 1,
                AgentItemTypeEnum.USER_MESSAGE, "x".repeat(8_000), NOW);
        AgentContextAssembly assembly = new AgentContextAssembler(
                new RecordingItems(List.of(large)), new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC),
                1_000, 256, 256, 128).assembleWithReport(thread(), null, "继续");

        assertFalse(assembly.items().isEmpty(), "预算不足时不能用空历史掩盖硬预算失败");
        assertTrue(assembly.report().estimatedTokens() > assembly.report().inputBudget());
    }

    @Test
    void checksReadGuardBeforeEveryHistoryPage() {
        AgentItemStore items = new RecordingItems(List.of(
                new AgentItemModel("item", "thread-1", "turn-1", 1,
                        AgentItemTypeEnum.USER_MESSAGE, "事实", NOW)
        ));

        assertThrows(AgentExecutionCancelledException.class,
                () -> new AgentContextAssembler(items, new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC),
                        2_000, 1_500, 256, 128)
                        .assembleWithReport(thread(), null, "继续",
                                () -> { throw new AgentExecutionCancelledException("cancelled"); }));
    }

    @Test
    void preservesToolCallResultPairAndFlagsUnpairedLegacyFact() {
        AgentItemModel call = new AgentItemModel("call", "thread-1", "turn-tool", 1,
                AgentItemTypeEnum.TOOL_CALL,
                "{\"tool\":\"lookup_order\",\"invocationId\":\"inv-1\"}", NOW);
        AgentItemModel result = new AgentItemModel("result", "thread-1", "turn-tool", 2,
                AgentItemTypeEnum.TOOL_RESULT,
                "{\"tool\":\"lookup_order\",\"invocationId\":\"inv-1\",\"status\":\"SUCCESS\"}", NOW);
        AgentContextAssembly paired = new AgentContextAssembler(
                new RecordingItems(List.of(call, result)), new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC),
                2_000, 1_500, 256, 128).assembleWithReport(thread(), null, "继续");

        assertFalse(paired.report().degraded());
        assertEquals(List.of(AgentItemTypeEnum.TOOL_CALL, AgentItemTypeEnum.TOOL_RESULT),
                paired.items().stream().map(AgentItemModel::type).toList());

        AgentContextAssembly orphan = new AgentContextAssembler(
                new RecordingItems(List.of(new AgentItemModel("orphan", "thread-1", "turn-tool", 1,
                        AgentItemTypeEnum.TOOL_RESULT, result.payload(), NOW))),
                new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC),
                2_000, 1_500, 256, 128).assembleWithReport(thread(), null, "继续");
        assertTrue(orphan.report().degraded());
        assertFalse(orphan.items().isEmpty());
    }

    @Test
    void marksAnUnpairedToolFactWithoutInventingAResult() {
        AgentItemModel call = new AgentItemModel("call", "thread-1", "turn-tool", 1,
                AgentItemTypeEnum.TOOL_CALL,
                "{\"tool\":\"lookup_order\",\"invocationId\":\"inv-1\"}", NOW);

        AgentContextAssembly assembly = new AgentContextAssembler(
                new RecordingItems(List.of(call)), new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC),
                2_000, 1_500, 256, 128).assembleWithReport(thread(), null, "继续");

        assertTrue(assembly.report().degraded());
        assertTrue(assembly.items().get(0).payload().contains("\"incomplete\":true"));
        assertEquals(0, assembly.items().stream()
                .filter(item -> item.type() == AgentItemTypeEnum.TOOL_RESULT).count());
    }

    @Test
    void keepsPersistedCurrentTurnToolFactsForDecisionCorrection() {
        List<AgentItemModel> history = List.of(
                new AgentItemModel("request", "thread-1", "turn-current", 1,
                        AgentItemTypeEnum.USER_MESSAGE, "当前请求", NOW),
                new AgentItemModel("call", "thread-1", "turn-current", 2,
                        AgentItemTypeEnum.TOOL_CALL,
                        "{\"tool\":\"lookup_order\",\"invocationId\":\"inv-1\"}", NOW),
                new AgentItemModel("result", "thread-1", "turn-current", 3,
                        AgentItemTypeEnum.TOOL_RESULT,
                        "{\"tool\":\"lookup_order\",\"invocationId\":\"inv-1\",\"status\":\"SUCCESS\"}", NOW)
        );

        AgentContextAssembly assembly = new AgentContextAssembler(
                new RecordingItems(history), new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC),
                2_000, 1_500, 256, 128)
                .assembleWithReport(thread(), "turn-current", "当前请求");

        assertTrue(assembly.items().stream().noneMatch(item -> item.type() == AgentItemTypeEnum.USER_MESSAGE));
        assertEquals(List.of(AgentItemTypeEnum.TOOL_CALL, AgentItemTypeEnum.TOOL_RESULT),
                assembly.items().stream().map(AgentItemModel::type).toList());
    }

    @Test
    void excludesInputsFromUnactivatedQueuedTurns() {
        List<AgentItemModel> history = List.of(
                new AgentItemModel("queued-message", "thread-1", "queued-turn", 1,
                        AgentItemTypeEnum.USER_MESSAGE, "尚未执行的请求", NOW),
                new AgentItemModel("queued-state", "thread-1", "queued-turn", 2,
                        AgentItemTypeEnum.TURN_STATE, "{\"status\":\"QUEUED\"}", NOW),
                new AgentItemModel("completed", "thread-1", "done-turn", 3,
                        AgentItemTypeEnum.USER_MESSAGE, "已经执行的事实", NOW)
        );

        AgentContextAssembly assembly = new AgentContextAssembler(
                new RecordingItems(history), new RecordingSnapshots(), Clock.fixed(NOW, ZoneOffset.UTC),
                2_000, 1_500, 256, 128).assembleWithReport(thread(), null, "继续");

        assertTrue(assembly.items().stream().noneMatch(item -> item.payload().contains("尚未执行")));
        assertTrue(assembly.items().stream().anyMatch(item -> item.payload().contains("已经执行")));
    }

    @Test
    void compactsOldFactsOnlyAfterPressureAndPersistsV2Snapshot() {
        List<AgentItemModel> history = new ArrayList<>();
        for (int sequence = 1; sequence <= 10; sequence++) {
            history.add(new AgentItemModel("item-" + sequence, "thread-1", "turn-" + sequence,
                    sequence, AgentItemTypeEnum.USER_MESSAGE, "事实-" + sequence + "x".repeat(900), NOW));
        }
        RecordingSnapshots snapshots = new RecordingSnapshots();
        AtomicInteger summaryCalls = new AtomicInteger();
        AgentContextCompactionSettings settings = new AgentContextCompactionSettings(
                true, 0.80, 0.16, 2_048, 8_000, 4_096, 1_024, 1);
        AgentContextAssembler assembler = new AgentContextAssembler(
                new RecordingItems(history), snapshots, Clock.fixed(NOW, ZoneOffset.UTC),
                2_000, 256, 128, (request, context) -> {
                    summaryCalls.incrementAndGet();
                    String reservation = context.reserveOutput(1);
                    context.settleOutput(reservation, 1);
                    return "保留用户目标和历史业务事实的摘要";
                }, settings);

        AgentExecutionContext execution = new AgentExecutionContext(
                Clock.fixed(NOW, ZoneOffset.UTC), NOW.plusSeconds(60), 8_192, 3);
        AgentContextAssembly assembly = assembler.assembleWithReport(
                thread(), "current", "当前问题", () -> { }, execution, false);

        assertTrue(assembly.report().compressed());
        assertEquals(1, summaryCalls.get());
        assertEquals(1, snapshots.saved.size());
        assertTrue(snapshots.saved.get(0).validV2());
        assertTrue(assembly.items().size() < history.size());
        assertTrue(assembly.summary().contains("摘要"));
        assertTrue(assembly.report().peakEstimatedTokens() > assembly.report().estimatedTokens());
    }

    @Test
    void doesNotAdvanceSummaryAcrossQueuedInputBarrier() {
        List<AgentItemModel> history = new ArrayList<>();
        for (int sequence = 1; sequence <= 5; sequence++) {
            history.add(new AgentItemModel("item-" + sequence, "thread-1", "turn-" + sequence,
                    sequence, AgentItemTypeEnum.USER_MESSAGE, "事实-" + sequence + "x".repeat(900), NOW));
        }
        history.add(new AgentItemModel("queued-state", "thread-1", "queued-turn", 6,
                AgentItemTypeEnum.TURN_STATE, "{\"status\":\"QUEUED\"}", NOW));
        history.add(new AgentItemModel("queued-input", "thread-1", "queued-turn", 7,
                AgentItemTypeEnum.USER_MESSAGE, "排队输入不得进入摘要覆盖", NOW));
        for (int sequence = 8; sequence <= 14; sequence++) {
            history.add(new AgentItemModel("item-" + sequence, "thread-1", "turn-" + sequence,
                    sequence, AgentItemTypeEnum.USER_MESSAGE, "事实-" + sequence + "x".repeat(900), NOW));
        }
        AtomicInteger through = new AtomicInteger();
        RecordingSnapshots snapshots = new RecordingSnapshots();
        AgentContextAssembler assembler = new AgentContextAssembler(
                new RecordingItems(history), snapshots, Clock.fixed(NOW, ZoneOffset.UTC),
                2_000, 256, 128, (request, context) -> {
                    through.set((int) request.throughSequence());
                    return "只保留可验证的旧事实";
                }, AgentContextCompactionSettings.defaults());

        assembler.assembleWithReport(thread(), null, "当前请求", () -> { },
                new AgentExecutionContext(Clock.fixed(NOW, ZoneOffset.UTC), NOW.plusSeconds(60)), false);

        assertTrue(through.get() > 0);
        assertTrue(through.get() < 7,
                "摘要水位不得越过尚未激活的排队输入");
    }

    @Test
    void generatedSummaryUsesV2SourceVersionAndIgnoresInvalidLatestBaseline() {
        List<AgentItemModel> history = new ArrayList<>();
        for (int sequence = 1; sequence <= 10; sequence++) {
            history.add(new AgentItemModel("item-" + sequence, "thread-1", "turn-" + sequence,
                    sequence, AgentItemTypeEnum.USER_MESSAGE, "事实-" + sequence + "x".repeat(900), NOW));
        }
        RecordingSnapshots snapshots = new RecordingSnapshots();
        snapshots.saved.add(new AgentContextSnapshotModel("invalid-v2-source", "thread-1", 4, 7,
                1, "旧摘要", NOW, 2, null, 1, 1, "context-summary-v1", 2_048));
        AgentContextAssembler assembler = new AgentContextAssembler(
                new RecordingItems(history), snapshots, Clock.fixed(NOW, ZoneOffset.UTC),
                2_000, 256, 128, (request, context) -> "新的可验证摘要",
                AgentContextCompactionSettings.defaults());

        assembler.assembleWithReport(thread(), null, "当前请求", () -> { },
                new AgentExecutionContext(Clock.fixed(NOW, ZoneOffset.UTC), NOW.plusSeconds(60)), false);

        AgentContextSnapshotModel saved = snapshots.saved.get(snapshots.saved.size() - 1);
        assertEquals("context-summary-v2", saved.promptVersion());
        assertTrue(saved.validV2());
        assertEquals(null, saved.baseSnapshotId());
        assertTrue(saved.version() > 7);
    }

    @Test
    void pressurePrunesToolResultBeforeCallingSummaryGatewayWhenItFits() {
        String result = "r".repeat(7_000);
        String payload = "{\"tool\":\"lookup_order\",\"invocationId\":\"inv-1\","
                + "\"status\":\"SUCCESS\",\"result\":\"" + result + "\"}";
        RecordingSnapshots snapshots = new RecordingSnapshots();
        AtomicInteger summaryCalls = new AtomicInteger();
        AgentContextCompactionSettings settings = new AgentContextCompactionSettings(
                true, 0.80, 0.16, 2_048, 6_000, 1_000, 500, 1);
        AgentContextAssembler assembler = new AgentContextAssembler(
                new RecordingItems(List.of(new AgentItemModel("tool", "thread-1", "turn-tool", 1,
                        AgentItemTypeEnum.TOOL_RESULT, payload, NOW))), snapshots,
                Clock.fixed(NOW, ZoneOffset.UTC), 2_000, 10_000, 256,
                (request, context) -> {
                    summaryCalls.incrementAndGet();
                    return "不应调用摘要";
                }, settings);

        AgentContextAssembly assembly = assembler.assembleWithReport(
                thread(), null, "查询", () -> { },
                new AgentExecutionContext(Clock.fixed(NOW, ZoneOffset.UTC), NOW.plusSeconds(60)), false);

        assertEquals(0, summaryCalls.get());
        assertTrue(assembly.items().get(0).payload().contains("compactionPruned"));
        assertTrue(assembly.items().get(0).payloadJson().length() <= 10_000);
        assertTrue(assembly.report().estimatedTokens() < 2_000);
        assertEquals(1, assembly.report().pressurePrunedToolResults());
    }

    @Test
    void keepsInterleavedFactsInsideTheSameToolBatchBoundary() {
        AgentModelContext context = new AgentModelContext("", List.of(
                new AgentItemModel("call", "thread-1", "turn-1", 1, AgentItemTypeEnum.TOOL_CALL,
                        "{\"tool\":\"lookup_order\",\"toolBatchId\":\"batch-1\"}", NOW),
                new AgentItemModel("fact", "thread-1", "turn-1", 2, AgentItemTypeEnum.ORDER_DETAIL,
                        "{\"orderId\":\"order-1\"}", NOW),
                new AgentItemModel("result", "thread-1", "turn-1", 3, AgentItemTypeEnum.TOOL_RESULT,
                        "{\"tool\":\"lookup_order\",\"toolBatchId\":\"batch-1\",\"result\":\"ok\"}", NOW),
                new AgentItemModel("next", "thread-1", "turn-1", 4, AgentItemTypeEnum.TOOL_CALL,
                        "{\"tool\":\"search_orders\",\"toolBatchId\":\"batch-2\"}", NOW)
        ), 4, 0, 4, "view");

        assertEquals(List.of(
                new AgentModelContext.ContextBoundary("turn-1", "batch-1", 1, 3),
                new AgentModelContext.ContextBoundary("turn-1", "batch-2", 4, 4)
        ), context.boundaries());
    }

    @Test
    void usesStructuredIncompleteSequencesInsteadOfPayloadTextForCompactionBarriers() {
        AgentModelContext context = new AgentModelContext("", List.of(
                new AgentItemModel("call", "thread-1", "turn-1", 1, AgentItemTypeEnum.TOOL_CALL,
                        "{\"tool\":\"lookup_order\",\"toolBatchId\":\"batch-1\"}", NOW),
                new AgentItemModel("result", "thread-1", "turn-1", 2, AgentItemTypeEnum.TOOL_RESULT,
                        "{\"tool\":\"lookup_order\",\"toolBatchId\":\"batch-1\",\"incomplete\":true}", NOW)
        ), 2, 0, 2, "view", Set.of(2L));

        assertFalse(context.compactionUnits().get(0).complete());
    }

    private AgentThreadModel thread() {
        return new AgentThreadModel(
                "thread-1", "user-1", "测试 Thread", AgentThreadStatusEnum.ACTIVE,
                null, null, 7, NOW, NOW
        );
    }

    private static class RecordingItems implements AgentItemStore {
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
        private RuntimeException findSnapshotFailure;

        @Override
        public Optional<AgentContextSnapshotModel> findLatestSnapshot(String userId, String threadId) {
            return saved.isEmpty() ? Optional.empty() : Optional.of(saved.get(saved.size() - 1));
        }

        @Override
        public Optional<AgentContextSnapshotModel> findSnapshot(String userId, String threadId, String snapshotId) {
            if (findSnapshotFailure != null) {
                throw findSnapshotFailure;
            }
            return saved.stream()
                    .filter(snapshot -> snapshot.snapshotId().equals(snapshotId))
                    .filter(snapshot -> snapshot.threadId().equals(threadId))
                    .findFirst();
        }

        @Override
        public void saveSnapshot(AgentContextSnapshotModel snapshot) {
            saved.add(snapshot);
        }
    }
}
