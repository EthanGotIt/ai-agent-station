package cn.ethan.core.agent.context;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 类型职责：承载模型可见的摘要、原始事实和固定读取水位，避免把派生视图当作持久事实。
 *
 * @author ethan
 * @date 2026-09-05
 */
public record AgentModelContext(
        String summary,
        List<AgentItemModel> items,
        long readWatermark,
        long snapshotThroughSequence,
        long coveredThroughSequence,
        String viewId,
        Set<Long> incompleteSequences
) {

    /** 兼容旧的直接组装调用；生产上下文由 Assembler 显式携带不完整 Item 序号。 */
    public AgentModelContext(
            String summary,
            List<AgentItemModel> items,
            long readWatermark,
            long snapshotThroughSequence,
            long coveredThroughSequence,
            String viewId
    ) {
        this(summary, items, readWatermark, snapshotThroughSequence, coveredThroughSequence, viewId, Set.of());
    }

    public AgentModelContext {
        summary = summary == null ? "" : summary;
        items = items == null ? List.of() : List.copyOf(items);
        viewId = viewId == null ? "raw" : viewId;
        incompleteSequences = incompleteSequences == null ? Set.of() : Set.copyOf(incompleteSequences);
    }

    public boolean compressed() {
        return !summary.isBlank();
    }

    /** 返回当前固定水位和视图版本组成的压缩候选标识。 */
    public String compactionKey() {
        return readWatermark + ":" + coveredThroughSequence + ":" + viewId;
    }

    /**
     * 返回模型视图中的完整压缩单元边界。带有 toolBatchId 的工具调用/结果按批次分组；
     * 旧 Item 没有批次标识时退回完整 Turn，避免摘要切出悬空 Tool 消息。
     */
    public List<ContextBoundary> boundaries() {
        return compactionUnits().stream().map(CompactionUnit::boundary).toList();
    }

    /**
     * 返回上下文唯一认可的压缩单元。单元边界和完整性在同一个结构化视图中计算，
     * 调用方不得再按 Turn 或原始列表自行回退切分。
     */
    public List<CompactionUnit> compactionUnits() {
        if (items.isEmpty()) {
            return List.of();
        }
        List<CompactionUnit> result = new ArrayList<>();
        String currentTurn = null;
        String currentBatch = null;
        long from = 0L;
        long through = 0L;
        List<AgentItemModel> currentItems = new ArrayList<>();
        for (AgentItemModel item : items) {
            String turn = item.turnId();
            String batch = toolBatchId(item);
            boolean turnChanged = !java.util.Objects.equals(currentTurn, turn);
            boolean enteringBatch = currentBatch == null && batch != null;
            boolean switchingBatch = currentBatch != null && batch != null
                    && !java.util.Objects.equals(currentBatch, batch);
            if (from > 0L && (turnChanged || enteringBatch || switchingBatch)) {
                result.add(unit(currentTurn, currentBatch, from, through, currentItems));
                from = 0L;
                currentItems = new ArrayList<>();
            }
            if (from == 0L) {
                currentTurn = turn;
                currentBatch = batch;
                from = item.sequence();
            }
            through = item.sequence();
            currentItems.add(item);
        }
        if (from > 0L) {
            result.add(unit(currentTurn, currentBatch, from, through, currentItems));
        }
        return List.copyOf(result);
    }

    private CompactionUnit unit(
            String turnId,
            String toolBatchId,
            long fromSequence,
            long throughSequence,
            List<AgentItemModel> values
    ) {
        boolean complete = values.stream().noneMatch(item -> incompleteSequences.contains(item.sequence()));
        return new CompactionUnit(new ContextBoundary(turnId, toolBatchId, fromSequence, throughSequence),
                List.copyOf(values), complete);
    }

    private String toolBatchId(AgentItemModel item) {
        if (item.type() != AgentItemTypeEnum.TOOL_CALL && item.type() != AgentItemTypeEnum.TOOL_RESULT) {
            return null;
        }
        String marker = "\"toolBatchId\":\"";
        int start = item.payloadJson().indexOf(marker);
        if (start < 0) {
            return null;
        }
        int valueStart = start + marker.length();
        int end = item.payloadJson().indexOf('"', valueStart);
        return end > valueStart ? item.payloadJson().substring(valueStart, end) : null;
    }

    public record ContextBoundary(
            String turnId,
            String toolBatchId,
            long fromSequence,
            long throughSequence
    ) {
    }

    public record CompactionUnit(
            ContextBoundary boundary,
            List<AgentItemModel> items,
            boolean complete
    ) {
        public CompactionUnit {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
