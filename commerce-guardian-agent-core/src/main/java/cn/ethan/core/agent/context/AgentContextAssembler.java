package cn.ethan.core.agent.context;

import cn.ethan.core.agent.execution.AgentExecutionCancelledException;
import cn.ethan.core.agent.execution.AgentExecutionContext;
import cn.ethan.core.agent.execution.AgentExecutionTimeoutException;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemPayloadModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadModel;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 类型职责：在固定 Item 水位内组装原始事实，并按 Harness 顺序完成工具裁剪和历史摘要。
 *
 * <p>摘要是可重建的模型视图；原始 Item 永远是事实来源，旧格式快照不会参与输入。</p>
 *
 * @author ethan
 * @date 2026-09-05
 */
public final class AgentContextAssembler {

    private static final int HISTORY_PAGE_SIZE = 300;
    private static final String SUMMARY_PROMPT_VERSION = "context-summary-v2";

    private final AgentItemStore items;
    private final AgentContextSnapshotStore snapshots;
    private final Clock clock;
    private final int contextMaxEstimatedTokens;
    private final int toolResultMaxCharacters;
    private final int outputReserveEstimatedTokens;
    private final AgentContextSummaryGateway summaryGateway;
    private final AgentContextCompactionSettings compaction;

    public AgentContextAssembler(
            AgentItemStore items,
            AgentContextSnapshotStore snapshots,
            Clock clock,
            int contextMaxEstimatedTokens,
            int ignoredSnapshotTriggerEstimatedTokens,
            int toolResultMaxCharacters,
            int outputReserveEstimatedTokens
    ) {
        this(items, snapshots, clock, contextMaxEstimatedTokens, toolResultMaxCharacters,
                outputReserveEstimatedTokens, null, AgentContextCompactionSettings.defaults());
    }

    public AgentContextAssembler(
            AgentItemStore items,
            AgentContextSnapshotStore snapshots,
            Clock clock,
            int contextMaxEstimatedTokens,
            int toolResultMaxCharacters,
            int outputReserveEstimatedTokens,
            AgentContextSummaryGateway summaryGateway,
            AgentContextCompactionSettings compaction
    ) {
        this.items = items;
        this.snapshots = snapshots;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.contextMaxEstimatedTokens = Math.max(1_000, contextMaxEstimatedTokens);
        this.toolResultMaxCharacters = Math.max(256, toolResultMaxCharacters);
        this.outputReserveEstimatedTokens = Math.max(128,
                Math.min(outputReserveEstimatedTokens, this.contextMaxEstimatedTokens - 1));
        this.summaryGateway = summaryGateway;
        this.compaction = compaction == null ? AgentContextCompactionSettings.defaults() : compaction;
    }

    /**
     * 返回供应商上下文溢出时允许的有限恢复次数；该配置属于上下文视图策略，供 Runtime 复用。
     */
    public int maxOverflowRetries() {
        return compaction.maxOverflowRetries();
    }

    public AgentContextAssembly assembleWithReport(
            AgentThreadModel thread, String currentTurnId, String currentInput
    ) {
        return assembleWithReport(thread, currentTurnId, currentInput, () -> { });
    }

    public AgentContextAssembly assembleWithReport(
            AgentThreadModel thread,
            String currentTurnId,
            String currentInput,
            AgentContextReadGuard readGuard
    ) {
        return assembleWithReport(thread, currentTurnId, currentInput, readGuard, null, false);
    }

    /** 由 Runtime 在初次组装和压力/供应商溢出恢复时调用。 */
    public AgentContextAssembly assembleWithReport(
            AgentThreadModel thread,
            String currentTurnId,
            String currentInput,
            AgentContextReadGuard readGuard,
            AgentExecutionContext executionContext,
            boolean forceCompaction
    ) {
        if (thread == null || items == null) {
            throw new AgentContextHistoryException("上下文历史读取依赖未装配");
        }
        AgentContextReadGuard guard = readGuard == null ? () -> { } : readGuard;
        guard.check();
        ContextView view = readView(thread, currentTurnId, guard);
        if (executionContext != null) {
            executionContext.setContextViewKey(view.compactionKey());
        }
        AgentContextAssembly assembled = buildAssembly(view, currentInput);
        if (!compaction.enabled() || (summaryGateway == null && !forceCompaction)) {
            return assembled;
        }
        int trigger = Math.max(1, (int) Math.floor(contextMaxEstimatedTokens * compaction.triggerRatio()));
        if (!forceCompaction && assembled.report().estimatedTokens() < trigger) {
            return assembled;
        }

        ContextView pruned = pruneToolResults(view);
        AgentContextAssembly prunedAssembly = buildAssembly(pruned, currentInput);
        if (forceCompaction && executionContext != null
                && executionContext.contextCompactionAttempted(view.compactionKey())) {
            // 当前固定水位和模型视图已经尝试过压力处理；供应商再次拒绝时不能重复调用摘要模型。
            return withPeak(prunedAssembly, assembled.report().estimatedTokens());
        }
        boolean pruneReduced = prunedAssembly.report().estimatedTokens()
                < assembled.report().estimatedTokens();
        if (pruneReduced && executionContext != null) {
            // 固定 Tool Result 限长属于历史视图的基础清洗；只有本轮压力裁剪才消费
            // 当前视图的压缩尝试额度，避免多个已限长结果仍达压力阈值时跳过摘要。
            executionContext.markContextCompactionAttempted(view.compactionKey());
        }
        if (prunedAssembly.report().estimatedTokens() < trigger
                && (!forceCompaction || pruneReduced)) {
            return withPeak(prunedAssembly, assembled.report().estimatedTokens());
        }
        if (summaryGateway == null || executionContext == null) {
            return withPeak(prunedAssembly, assembled.report().estimatedTokens());
        }

        CompactionCandidate candidate = chooseCandidate(pruned, currentTurnId, currentInput);
        if (candidate.facts().isEmpty()) {
            return withPeak(prunedAssembly, assembled.report().estimatedTokens());
        }
        executionContext.checkActive();
        String previousSummary = view.summary();
        long sourceFrom = view.snapshotThroughSequence() + 1L;
        // 来源估算只描述快照覆盖的历史，不把本轮请求混入恢复校验元数据。
        long sourceEstimate = estimateFacts(previousSummary, candidate.facts(), null);
        int availableOutput = remainingOutput(executionContext);
        int summaryOutput = Math.min(compaction.summaryMaxOutputTokens(), availableOutput);
        if (summaryOutput < 128) {
            return withPeak(prunedAssembly, assembled.report().estimatedTokens());
        }
        AgentContextSummaryRequest request = new AgentContextSummaryRequest(
                thread.threadId(), previousSummary, candidate.facts(), sourceFrom,
                candidate.throughSequence(), SUMMARY_PROMPT_VERSION,
                summaryOutput);
        // 本固定水位和视图只允许一次摘要尝试；失败后保留最后有效视图，不在 Advisor 重入时重复调用摘要模型。
        executionContext.markContextCompactionAttempted(view.compactionKey());
        String summary;
        try {
            summary = summaryGateway.summarize(request, executionContext);
        } catch (AgentExecutionCancelledException | AgentExecutionTimeoutException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            return withPeak(prunedAssembly, assembled.report().estimatedTokens());
        }
        if (summary == null || summary.isBlank()) {
            return withPeak(prunedAssembly, assembled.report().estimatedTokens());
        }
        String normalizedSummary = summary.trim();
        int summaryEstimate = AgentContextTokenEstimator.estimateCharacters(normalizedSummary.length());
        if (summaryEstimate <= 0 || summaryEstimate > request.maxOutputTokens()) {
            return withPeak(prunedAssembly, assembled.report().estimatedTokens());
        }
        long retainedEstimate = estimateFacts("", candidate.retainedFacts(), currentInput);
        if (summaryEstimate + retainedEstimate >= prunedAssembly.report().estimatedTokens()) {
            return withPeak(prunedAssembly, assembled.report().estimatedTokens());
        }

        // CAS 基线在摘要调用前已经由 readView 固定；摘要生成期间不重新读取最新快照。
        AgentContextSnapshotModel baseSnapshot = view.baseSnapshot();
        AgentContextSnapshotModel casBaseline = view.casBaselineSnapshot();
        long nextVersion = Math.max(baseSnapshot == null ? 0L : baseSnapshot.version(),
                casBaseline == null ? 0L : casBaseline.version()) + 1L;
        AgentContextSnapshotModel snapshot = new AgentContextSnapshotModel(
                UUID.randomUUID().toString(), thread.threadId(), candidate.throughSequence(), nextVersion,
                summaryEstimate, normalizedSummary, clock.instant(), 2,
                baseSnapshot == null ? null : baseSnapshot.snapshotId(),
                sourceFrom, (int) Math.min(Integer.MAX_VALUE, sourceEstimate), SUMMARY_PROMPT_VERSION,
                request.maxOutputTokens());
        boolean saved;
        try {
            saved = snapshots != null && snapshots.saveSnapshotIfCurrent(
                    thread.userId(), thread.threadId(), casBaseline == null ? null : casBaseline.snapshotId(), snapshot);
        } catch (RuntimeException persistenceFailure) {
            // 摘要是派生视图；持久化失败时保留本次仍可验证的模型视图，下一轮重新读取原始事实。
            saved = false;
        }
        if (!saved) {
            // 并发压缩只允许一个 CAS 胜者；本次摘要结果直接丢弃，读取胜者视图避免在同一 Turn
            // 再次调用摘要模型。若胜者暂时不可读，则继续使用本次尚未持久化的最后有效视图。
            AgentContextSnapshotModel winner;
            try {
                winner = snapshots == null ? null
                        : readWinningSnapshot(thread, view.watermark());
            } catch (RuntimeException winnerReadFailure) {
                winner = null;
            }
            if (winner != null && winner.throughSequence() <= view.watermark()) {
                return withPeak(rebaseToSnapshot(pruned, winner, currentInput),
                        assembled.report().estimatedTokens());
            }
            return withPeak(prunedAssembly, assembled.report().estimatedTokens());
        }
        ContextView compacted = new ContextView(
                pruned.rawItems(), candidate.retainedFacts(), view.watermark(), pruned.complete(),
                0, pruned.incompleteSequences(), pruned.prunedToolResults(),
                pruned.pressurePrunedToolResults(), normalizedSummary,
                candidate.throughSequence(), snapshot.snapshotId(),
                snapshot, snapshot);
        return withPeak(buildAssembly(compacted, currentInput), assembled.report().estimatedTokens());
    }

    private AgentContextAssembly withPeak(AgentContextAssembly assembly, int peak) {
        return new AgentContextAssembly(assembly.items(), assembly.summary(), assembly.modelContext(),
                assembly.report().withPeakEstimatedTokens(peak));
    }

    private AgentContextAssembly rebaseToSnapshot(
            ContextView view,
            AgentContextSnapshotModel snapshot,
            String currentInput
    ) {
        List<AgentItemModel> afterSnapshot = view.visibleItems().stream()
                .filter(item -> item.sequence() > snapshot.throughSequence())
                .toList();
        ToolPairing pairing = pairToolFacts(afterSnapshot);
        BoundedFacts bounded = boundToolFacts(pairing.items(), view.prunedToolResults(), view.viewId());
        ContextView rebased = new ContextView(view.rawItems(), bounded.items(), view.watermark(),
                pairing.incompleteSequences().isEmpty(), pairing.unpairedCount(), pairing.incompleteSequences(),
                bounded.count(), view.pressurePrunedToolResults(), snapshot.summary(),
                snapshot.throughSequence(), snapshot.snapshotId(), snapshot, snapshot);
        return buildAssembly(rebased, currentInput);
    }

    private AgentContextAssembly buildAssembly(ContextView view, String currentInput) {
        int inputBudget = Math.max(1, contextMaxEstimatedTokens - outputReserveEstimatedTokens);
        long characters = currentInput == null ? 0L : currentInput.length();
        characters = saturatingAdd(characters, view.summary().length());
        for (AgentItemModel item : view.visibleItems()) {
            characters = saturatingAdd(characters, item.payloadJson().length());
        }
        int estimate = AgentContextTokenEstimator.estimateCharacters(characters);
        boolean fits = estimate <= inputBudget;
        boolean degraded = view.unpairedToolFacts() > 0 || !fits;
        // 超出预算时仍保留完整模型视图，交由 Runtime 在最终请求检查处停止；
        // 不能把历史静默替换为空列表后继续发送只含当前请求的模型调用。
        List<AgentItemModel> modelItems = view.visibleItems();
        AgentContextBudgetReport report = new AgentContextBudgetReport(
                estimate, inputBudget, view.snapshotThroughSequence(), !view.summary().isBlank(), degraded,
                view.prunedToolResults(), view.watermark(), view.coveredThroughSequence(), view.rawItems().size(),
                view.complete(), estimate, view.pressurePrunedToolResults());
        AgentModelContext modelContext = new AgentModelContext(view.summary(), modelItems, view.watermark(),
                view.snapshotThroughSequence(), view.coveredThroughSequence(), view.viewId(),
                view.incompleteSequences());
        return new AgentContextAssembly(modelItems, view.summary(), modelContext, report);
    }

    private ContextView readView(AgentThreadModel thread, String currentTurnId, AgentContextReadGuard guard) {
        long watermark;
        try {
            watermark = Math.max(0L, items.captureWatermark(thread.userId(), thread.threadId()));
        } catch (RuntimeException failure) {
            if (failure instanceof AgentExecutionCancelledException
                    || failure instanceof AgentExecutionTimeoutException) throw failure;
            throw new AgentContextHistoryException("无法捕获上下文历史水位", failure);
        }
        AgentContextSnapshotModel latestSnapshot;
        try {
            latestSnapshot = snapshots == null ? null
                    : snapshots.findLatestSnapshot(thread.userId(), thread.threadId()).orElse(null);
        } catch (RuntimeException failure) {
            if (failure instanceof AgentExecutionCancelledException
                    || failure instanceof AgentExecutionTimeoutException) throw failure;
            throw new AgentContextHistoryException("无法读取上下文摘要快照", failure);
        }
        // 快照水位不能领先于本次固定水位；否则必须从原始 Items 重建，不能跳过早期事实。
        AgentContextSnapshotModel snapshot = validSnapshotChain(
                thread.userId(), thread.threadId(), latestSnapshot, watermark) ? latestSnapshot : null;
        long snapshotThrough = snapshot == null ? 0L : Math.max(0L, snapshot.throughSequence());
        String summary = snapshot == null ? "" : snapshot.summary();
        List<AgentItemModel> raw = readItems(thread, snapshotThrough, watermark, guard);
        Map<String, String> turnStatuses = latestTurnStatuses(raw);
        List<AgentItemModel> visible = new ArrayList<>();
        for (AgentItemModel item : raw) {
            if (isCurrentRequest(item, currentTurnId)) continue;
            if (isUnactivatedQueuedTurn(item, currentTurnId, turnStatuses) || !modelVisible(item)) continue;
            visible.add(item);
        }
        ToolPairing pairing = pairToolFacts(visible);
        String baseViewId = snapshot == null ? "raw" : snapshot.snapshotId();
        BoundedFacts bounded = boundToolFacts(pairing.items(), 0, baseViewId);
        return new ContextView(List.copyOf(raw), bounded.items(), watermark,
                pairing.incompleteSequences().isEmpty(), pairing.unpairedCount(), pairing.incompleteSequences(),
                bounded.count(), 0, summary, snapshotThrough, bounded.viewId(), snapshot, latestSnapshot);
    }

    private List<AgentItemModel> readItems(AgentThreadModel thread, long start, long watermark,
                                           AgentContextReadGuard guard) {
        List<AgentItemModel> raw = new ArrayList<>();
        long cursor = Math.max(0L, start);
        while (cursor < watermark) {
            guard.check();
            List<AgentItemModel> page;
            try {
                page = items.listItemsThrough(thread.userId(), thread.threadId(), cursor, watermark,
                        HISTORY_PAGE_SIZE);
            } catch (RuntimeException failure) {
                if (failure instanceof AgentExecutionCancelledException
                        || failure instanceof AgentExecutionTimeoutException) throw failure;
                throw new AgentContextHistoryException("无法读取上下文历史页面", failure);
            }
            if (page == null || page.isEmpty()) throw new AgentContextHistoryException("上下文历史页面未覆盖固定水位");
            if (page.size() > HISTORY_PAGE_SIZE) throw new AgentContextHistoryException("上下文历史页面超过固定分页大小");
            long previous = cursor;
            for (AgentItemModel item : page) {
                if (item == null || item.sequence() <= previous || item.sequence() > watermark) {
                    throw new AgentContextHistoryException("上下文历史 Sequence 未严格前进");
                }
                if (item.sequence() != previous + 1L) {
                    throw new AgentContextHistoryException("上下文历史 Sequence 存在未覆盖的连续事实");
                }
                raw.add(item);
                previous = item.sequence();
            }
            if (previous <= cursor) throw new AgentContextHistoryException("上下文历史游标未前进");
            cursor = previous;
        }
        return List.copyOf(raw);
    }

    private AgentContextSnapshotModel readWinningSnapshot(AgentThreadModel thread, long watermark) {
        if (snapshots == null) {
            return null;
        }
        AgentContextSnapshotModel latest = snapshots.findLatestSnapshot(thread.userId(), thread.threadId())
                .orElse(null);
        return validSnapshotChain(thread.userId(), thread.threadId(), latest, watermark) ? latest : null;
    }

    /**
     * 校验快照归属和增量链，避免一个失效的派生视图静默跳过原始事实。
     */
    private boolean validSnapshotChain(
            String userId,
            String threadId,
            AgentContextSnapshotModel snapshot,
            long watermark
    ) {
        if (snapshot == null || !threadId.equals(snapshot.threadId())
                || !snapshot.validV2() || snapshot.throughSequence() > watermark) {
            return false;
        }
        Set<String> visited = new HashSet<>();
        AgentContextSnapshotModel current = snapshot;
        for (int depth = 0; current != null && depth < 128; depth++) {
            if (!visited.add(current.snapshotId()) || !threadId.equals(current.threadId())
                    || !current.validV2() || current.throughSequence() > watermark) {
                return false;
            }
            String baseId = current.baseSnapshotId();
            if (baseId == null || baseId.isBlank()) {
                return current.sourceFromSequence() == 1L;
            }
            if (snapshots == null) {
                return false;
            }
            AgentContextSnapshotModel base = snapshots.findSnapshot(userId, threadId, baseId).orElse(null);
            if (base == null || base.throughSequence() >= current.throughSequence()
                    || base.version() >= current.version()
                    || current.sourceFromSequence() != base.throughSequence() + 1L) {
                return false;
            }
            current = base;
        }
        return false;
    }

    private ContextView pruneToolResults(ContextView view) {
        List<AgentItemModel> pruned = new ArrayList<>(view.visibleItems().size());
        int prunedCount = view.prunedToolResults();
        int pressurePrunedCount = view.pressurePrunedToolResults();
        for (AgentItemModel item : view.visibleItems()) {
            AgentItemModel bounded = pressurePruneToolResult(item);
            if (bounded != item) {
                prunedCount++;
                pressurePrunedCount++;
            }
            pruned.add(bounded);
        }
        String viewId = prunedCount > view.prunedToolResults()
                ? view.viewId() + ":pruned:" + prunedCount : view.viewId();
        return new ContextView(view.rawItems(), List.copyOf(pruned), view.watermark(), view.complete(),
                view.unpairedToolFacts(), view.incompleteSequences(), prunedCount, pressurePrunedCount, view.summary(),
                view.snapshotThroughSequence(), viewId,
                view.baseSnapshot(), view.casBaselineSnapshot());
    }

    private BoundedFacts boundToolFacts(List<AgentItemModel> values, int initialCount, String baseViewId) {
        List<AgentItemModel> bounded = new ArrayList<>(values.size());
        int count = Math.max(0, initialCount);
        for (AgentItemModel item : values) {
            AgentItemModel value = boundToolResult(item);
            if (value != item) {
                count++;
            }
            bounded.add(value);
        }
        String viewId = baseViewId == null ? "raw" : baseViewId;
        if (count > initialCount) {
            viewId = viewId + ":bounded:" + count;
        }
        return new BoundedFacts(List.copyOf(bounded), count, viewId);
    }

    private AgentItemModel pressurePruneToolResult(AgentItemModel item) {
        if (item.type() != AgentItemTypeEnum.TOOL_RESULT
                || item.payloadJson().length() < compaction.toolPruneThresholdCharacters()) return item;
        String result = extractString(item.payload(), "result");
        if (result == null || result.length() <= compaction.toolPruneHeadCharacters()
                + compaction.toolPruneTailCharacters()) return item;
        String head = result.substring(0, Math.min(compaction.toolPruneHeadCharacters(), result.length()));
        String tail = result.substring(Math.max(0, result.length() - compaction.toolPruneTailCharacters()));
        String tool = extractString(item.payload(), "tool");
        String invocationId = extractString(item.payload(), "invocationId");
        String toolBatchId = extractString(item.payload(), "toolBatchId");
        String status = extractString(item.payload(), "status");
        boolean incomplete = item.payload().contains("\"incomplete\":true")
                || item.payload().contains("\"incomplete\": true");
        boolean alreadyTruncated = item.payload().contains("\"truncated\":true")
                || item.payload().contains("\"truncated\": true");
        int envelopeOverhead = AgentItemPayloadModel.ensure(AgentItemTypeEnum.TOOL_RESULT, "{}").length() - 2;
        int innerLimit = Math.max(64, toolResultMaxCharacters - envelopeOverhead);
        String payload = prunedToolResultJson(tool, invocationId, toolBatchId, status,
                head, tail, result.length(), incomplete, alreadyTruncated);
        while (payload.length() > innerLimit
                && (!head.isEmpty() || !tail.isEmpty() || tool != null && !tool.isEmpty()
                || invocationId != null && !invocationId.isEmpty()
                || toolBatchId != null && !toolBatchId.isEmpty()
                || status != null && !status.isEmpty())) {
            if (head.length() >= tail.length() && !head.isEmpty()) {
                head = head.substring(0, head.length() - 1);
            } else if (!tail.isEmpty()) {
                tail = tail.substring(1);
            } else if (tool != null && !tool.isEmpty()) {
                tool = tool.substring(0, tool.length() - 1);
            } else if (invocationId != null && !invocationId.isEmpty()) {
                invocationId = invocationId.substring(0, invocationId.length() - 1);
            } else if (toolBatchId != null && !toolBatchId.isEmpty()) {
                toolBatchId = toolBatchId.substring(0, toolBatchId.length() - 1);
            } else if (status != null && !status.isEmpty()) {
                status = status.substring(0, status.length() - 1);
            }
            payload = prunedToolResultJson(tool, invocationId, toolBatchId, status,
                    head, tail, result.length(), incomplete, alreadyTruncated);
        }
        return new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), item.sequence(), item.type(),
                payload, item.createdAt());
    }

    private String prunedToolResultJson(
            String tool,
            String invocationId,
            String toolBatchId,
            String status,
            String head,
            String tail,
            int originalLength,
            boolean incomplete,
            boolean alreadyTruncated
    ) {
        return "{\"tool\":\"" + escape(tool) + "\",\"invocationId\":\""
                + escape(invocationId) + "\",\"status\":\"" + escape(status)
                + "\",\"resultHead\":\"" + escape(head) + "\",\"resultTail\":\""
                + escape(tail) + "\",\"originalLength\":" + originalLength
                + (toolBatchId == null ? "" : ",\"toolBatchId\":\"" + escape(toolBatchId) + "\"")
                + (alreadyTruncated ? ",\"truncated\":true" : "")
                + (incomplete ? ",\"incomplete\":true" : "")
                + ",\"compactionPruned\":true}";
    }

    private CompactionCandidate chooseCandidate(ContextView view, String currentTurnId, String currentInput) {
        List<AgentItemModel> values = view.visibleItems();
        AgentModelContext structured = new AgentModelContext(view.summary(), values, view.watermark(),
                view.snapshotThroughSequence(), view.coveredThroughSequence(), view.viewId(),
                view.incompleteSequences());
        List<AgentModelContext.CompactionUnit> units = structured.compactionUnits();
        if (units.isEmpty()) {
            return new CompactionCandidate(List.of(), values, 0L);
        }
        Set<Long> visibleSequences = values.stream().map(AgentItemModel::sequence).collect(java.util.stream.Collectors.toSet());
        long barrierSequence = Long.MAX_VALUE;
        for (AgentItemModel item : view.rawItems()) {
            if (modelVisible(item) && !visibleSequences.contains(item.sequence())) {
                barrierSequence = Math.min(barrierSequence, item.sequence());
            }
        }
        for (AgentModelContext.CompactionUnit unit : units) {
            if (!unit.complete()) {
                barrierSequence = Math.min(barrierSequence, unit.boundary().fromSequence());
            }
        }
        // 当前请求由模型视图单独渲染；它不能挤占历史尾部的保留比例。
        long retainedCharacters = 0L;
        long retainTarget = Math.max(1L, (long) (contextMaxEstimatedTokens * compaction.retainRatio() * 2));
        int tailStart = values.size();
        for (int unitIndex = units.size() - 1; unitIndex >= 0; unitIndex--) {
            AgentModelContext.CompactionUnit unit = units.get(unitIndex);
            List<AgentItemModel> unitItems = unit.items();
            boolean currentTurn = currentTurnId != null
                    && unitItems.stream().anyMatch(item -> currentTurnId.equals(item.turnId()));
            if (currentTurn || retainedCharacters < retainTarget) {
                retainedCharacters = saturatingAdd(retainedCharacters,
                        unitItems.stream().mapToLong(item -> item.payloadJson().length()).sum());
                tailStart = values.indexOf(unitItems.get(0));
                continue;
            }
            break;
        }
        if (tailStart > 0 && barrierSequence != Long.MAX_VALUE) {
            int barrierIndex = 0;
            while (barrierIndex < values.size() && values.get(barrierIndex).sequence() < barrierSequence) {
                barrierIndex++;
            }
            tailStart = Math.min(tailStart, barrierIndex);
        }
        // 结构化单元已经是唯一合法的边界算法；屏障落在单元中间时回退到单元起点。
        if (tailStart > 0 && tailStart < values.size()) {
            long sequence = values.get(tailStart).sequence();
            for (AgentModelContext.CompactionUnit unit : units) {
                if (sequence >= unit.boundary().fromSequence()
                        && sequence <= unit.boundary().throughSequence()) {
                    tailStart = values.indexOf(unit.items().get(0));
                    break;
                }
            }
        }
        if (tailStart <= 0) return new CompactionCandidate(List.of(), values, 0L);
        List<AgentItemModel> facts = List.copyOf(values.subList(0, tailStart));
        List<AgentItemModel> retained = List.copyOf(values.subList(tailStart, values.size()));
        return new CompactionCandidate(facts, retained, facts.get(facts.size() - 1).sequence());
    }

    private long estimateFacts(String summary, List<AgentItemModel> facts, String currentInput) {
        long characters = summary == null ? 0L : summary.length();
        characters = saturatingAdd(characters, currentInput == null ? 0L : currentInput.length());
        for (AgentItemModel item : facts) characters = saturatingAdd(characters, item.payloadJson().length());
        return AgentContextTokenEstimator.estimateCharacters(characters);
    }

    private int remainingOutput(AgentExecutionContext executionContext) {
        long available = (long) executionContext.maxOutputTokens() - executionContext.outputTokensUsed();
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, available));
    }

    private boolean isCurrentRequest(AgentItemModel item, String currentTurnId) {
        return currentTurnId != null && currentTurnId.equals(item.turnId())
                && item.type() == AgentItemTypeEnum.USER_MESSAGE;
    }

    private Map<String, String> latestTurnStatuses(List<AgentItemModel> raw) {
        Map<String, String> statuses = new HashMap<>();
        for (AgentItemModel item : raw) {
            if (item.turnId() != null && item.type() == AgentItemTypeEnum.TURN_STATE) {
                String status = extractString(item.payload(), "status");
                if (status != null) statuses.put(item.turnId(), status);
            }
        }
        return statuses;
    }

    private boolean isUnactivatedQueuedTurn(AgentItemModel item, String currentTurnId,
                                            Map<String, String> turnStatuses) {
        return item.turnId() != null && !item.turnId().equals(currentTurnId)
                && "QUEUED".equals(turnStatuses.get(item.turnId()));
    }

    private ToolPairing pairToolFacts(List<AgentItemModel> values) {
        Map<ToolInvocationKey, Deque<Integer>> openCalls = new HashMap<>();
        Set<Integer> incompleteIndexes = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            AgentItemModel item = values.get(index);
            if (item.type() != AgentItemTypeEnum.TOOL_CALL && item.type() != AgentItemTypeEnum.TOOL_RESULT) continue;
            String invocationId = extractString(item.payload(), "invocationId");
            if (invocationId == null || invocationId.isBlank()) {
                incompleteIndexes.add(index);
                continue;
            }
            ToolInvocationKey key = new ToolInvocationKey(item.turnId(), invocationId);
            if (item.type() == AgentItemTypeEnum.TOOL_CALL) {
                openCalls.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(index);
            } else {
                Deque<Integer> outstanding = openCalls.get(key);
                if (outstanding == null || outstanding.isEmpty()) incompleteIndexes.add(index);
                else {
                    outstanding.removeFirst();
                    if (outstanding.isEmpty()) openCalls.remove(key);
                }
            }
        }
        openCalls.values().forEach(incompleteIndexes::addAll);
        List<AgentItemModel> marked = new ArrayList<>(values.size());
        Set<Long> incompleteSequences = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            AgentItemModel item = values.get(index);
            if (incompleteIndexes.contains(index)) {
                incompleteSequences.add(item.sequence());
                marked.add(markIncompleteToolFact(item));
            } else {
                marked.add(item);
            }
        }
        return new ToolPairing(List.copyOf(marked), incompleteIndexes.size(), Set.copyOf(incompleteSequences));
    }

    private AgentItemModel markIncompleteToolFact(AgentItemModel item) {
        String payload = item.payload();
        if (payload.contains("\"incomplete\":true")) return item;
        String marked = payload.contains("\"data\":{") && payload.endsWith("}}")
                ? payload.substring(0, payload.length() - 2) + ",\"incomplete\":true}}"
                : AgentItemPayloadModel.ensure(item.type(), "{\"incomplete\":true,\"raw\":\""
                + escape(payload) + "\"}");
        return new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), item.sequence(),
                item.type(), marked, item.createdAt());
    }

    private boolean modelVisible(AgentItemModel item) {
        return switch (item.type()) {
            case USER_MESSAGE, ASSISTANT_MESSAGE, TOOL_CALL, TOOL_RESULT,
                    WORKFLOW_STARTED, QUESTION_CARD, WORKFLOW_QUESTION, WORKFLOW_CHECKPOINT,
                    WORKFLOW_RESULT, EXTERNAL_ACTION_STATUS, WORKFLOW_DECISION,
                    ORDER_LIST, ORDER_DETAIL, LOGISTICS_TIMELINE, WORKFLOW_STEP, AGENT_DECISION -> true;
            case TURN_STATE, QUESTION_ANSWER, WORKFLOW_ANSWER, ORDER_ACTION_REQUEST,
                    AGENT_CONTINUATION, EXECUTION_EVENT, ERROR -> false;
        };
    }

    private AgentItemModel boundToolResult(AgentItemModel item) {
        if (item.type() != AgentItemTypeEnum.TOOL_RESULT
                || item.payloadJson().length() <= toolResultMaxCharacters) return item;
        String tool = extractString(item.payload(), "tool");
        String invocationId = extractString(item.payload(), "invocationId");
        String toolBatchId = extractString(item.payload(), "toolBatchId");
        String status = extractString(item.payload(), "status");
        String source = extractString(item.payload(), "result");
        if (source == null) source = item.payloadJson();
        int envelopeOverhead = AgentItemPayloadModel.ensure(AgentItemTypeEnum.TOOL_RESULT, "{}").length() - 2;
        int innerLimit = Math.max(1, toolResultMaxCharacters - envelopeOverhead);
        boolean incomplete = item.payload().contains("\"incomplete\":true");
        String payload = boundedToolResultJson(tool, invocationId, toolBatchId, status, source, incomplete, innerLimit);
        return new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), item.sequence(), item.type(),
                payload, item.createdAt());
    }

    private String boundedToolResultJson(String tool, String invocationId, String toolBatchId, String status,
                                         String result, boolean incomplete, int maxCharacters) {
        String safeTool = tool == null ? "" : tool;
        String safeInvocationId = invocationId == null ? "" : invocationId;
        String safeToolBatchId = toolBatchId == null ? "" : toolBatchId;
        String safeStatus = status == null ? "" : status;
        String source = result == null ? "" : result;
        String prefix = boundedToolResultPrefix(safeTool, safeInvocationId, safeToolBatchId, safeStatus);
        String suffix = "\",\"truncated\":true" + (incomplete ? ",\"incomplete\":true" : "") + "}";
        int length = Math.min(source.length(), Math.max(0, maxCharacters - prefix.length() - suffix.length()));
        String bounded = source.substring(0, length);
        while (length > 0 && prefix.length() + escape(bounded).length() + suffix.length() > maxCharacters) {
            bounded = source.substring(0, --length);
        }
        String payload = prefix + escape(bounded) + suffix;
        while (payload.length() > maxCharacters) {
            if (!safeTool.isEmpty()) safeTool = safeTool.substring(0, safeTool.length() - 1);
            else if (!safeInvocationId.isEmpty()) safeInvocationId = safeInvocationId.substring(0, safeInvocationId.length() - 1);
            else if (!safeToolBatchId.isEmpty()) safeToolBatchId = safeToolBatchId.substring(0, safeToolBatchId.length() - 1);
            else if (!safeStatus.isEmpty()) safeStatus = safeStatus.substring(0, safeStatus.length() - 1);
            else if (!bounded.isEmpty()) bounded = bounded.substring(0, bounded.length() - 1);
            else break;
            prefix = boundedToolResultPrefix(safeTool, safeInvocationId, safeToolBatchId, safeStatus);
            payload = prefix + escape(bounded) + suffix;
        }
        return payload;
    }

    private String boundedToolResultPrefix(String tool, String invocationId, String toolBatchId, String status) {
        return "{\"tool\":\"" + escape(tool) + "\",\"invocationId\":\""
                + escape(invocationId)
                + (toolBatchId.isBlank() ? "" : "\",\"toolBatchId\":\"" + escape(toolBatchId))
                + "\",\"status\":\"" + escape(status) + "\",\"result\":\"";
    }

    private String extractString(String payload, String field) {
        if (payload == null || field == null) return null;
        String marker = "\"" + field + "\":\"";
        int start = payload.indexOf(marker);
        if (start < 0) return null;
        int valueStart = start + marker.length();
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int index = valueStart; index < payload.length(); index++) {
            char current = payload.charAt(index);
            if (escaped) {
                switch (current) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    default -> value.append(current);
                }
                escaped = false;
            } else if (current == '\\') escaped = true;
            else if (current == '"') return value.toString();
            else value.append(current);
        }
        return null;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    private long saturatingAdd(long left, long right) {
        if (right <= 0L) return left;
        return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private record ContextView(
            List<AgentItemModel> rawItems,
            List<AgentItemModel> visibleItems,
            long watermark,
            boolean complete,
            int unpairedToolFacts,
            Set<Long> incompleteSequences,
            int prunedToolResults,
            int pressurePrunedToolResults,
            String summary,
            long snapshotThroughSequence,
            String viewId,
            AgentContextSnapshotModel baseSnapshot,
            AgentContextSnapshotModel casBaselineSnapshot
    ) {
        private ContextView {
            rawItems = rawItems == null ? List.of() : List.copyOf(rawItems);
            visibleItems = visibleItems == null ? List.of() : List.copyOf(visibleItems);
            incompleteSequences = incompleteSequences == null ? Set.of() : Set.copyOf(incompleteSequences);
            summary = summary == null ? "" : summary;
            viewId = viewId == null ? "raw" : viewId;
        }

        long coveredThroughSequence() {
            return visibleItems.isEmpty() ? snapshotThroughSequence
                    : visibleItems.get(visibleItems.size() - 1).sequence();
        }

        String compactionKey() {
            return watermark + ":" + coveredThroughSequence() + ":" + viewId;
        }
    }

    private record ToolPairing(List<AgentItemModel> items, int unpairedCount, Set<Long> incompleteSequences) { }

    private record BoundedFacts(List<AgentItemModel> items, int count, String viewId) { }

    private record ToolInvocationKey(String turnId, String invocationId) { }

    private record CompactionCandidate(
            List<AgentItemModel> facts,
            List<AgentItemModel> retainedFacts,
            long throughSequence
    ) { }
}
