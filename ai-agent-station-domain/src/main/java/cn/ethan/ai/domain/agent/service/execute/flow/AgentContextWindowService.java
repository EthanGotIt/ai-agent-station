package cn.ethan.ai.domain.agent.service.execute.flow;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.valobj.ContextGuardResultVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextWindowGuardVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上下文预算与历史压缩服务
 */
@Service
public class AgentContextWindowService {

    public ContextGuardResultVO prepareStepOutputs(AgentRunAggregate run) {
        if (run == null || run.stepOutputs().isEmpty()) {
            return ContextGuardResultVO.builder()
                    .stepOutputs(Collections.emptyMap())
                    .compressed(false)
                    .originalChars(0)
                    .compressedChars(0)
                    .historySummary("")
                    .build();
        }

        ContextWindowGuardVO guard = run.getContextWindowGuard();
        Map<String, String> rawOutputs = run.stepOutputs();
        int originalChars = calculateContextUnits(rawOutputs, guard);
        if (!guard.shouldCompactHistory(originalChars)) {
            if (guard.isHistoryCompacted() && StringUtils.isNotBlank(guard.getHistorySummary())) {
                Map<String, String> compactedOutputs = new LinkedHashMap<>();
                compactedOutputs.put("history_summary", guard.getHistorySummary());
                appendRecentOutputs(rawOutputs, compactedOutputs);
                int compressedChars = calculateContextUnits(compactedOutputs, guard);
                guard.updateHistorySnapshot(originalChars, compressedChars, guard.getHistorySummary());
                return ContextGuardResultVO.builder()
                        .stepOutputs(compactedOutputs)
                        .compressed(true)
                        .originalChars(originalChars)
                        .compressedChars(compressedChars)
                        .historySummary(guard.getHistorySummary())
                        .build();
            }
            guard.updateHistorySnapshot(originalChars, originalChars, "");
            return ContextGuardResultVO.builder()
                    .stepOutputs(rawOutputs)
                    .compressed(false)
                    .originalChars(originalChars)
                    .compressedChars(originalChars)
                    .historySummary("")
                    .build();
        }

        String historySummary = buildHistorySummary(rawOutputs, guard.getSummaryMaxChars());
        Map<String, String> compactedOutputs = new LinkedHashMap<>();
        compactedOutputs.put("history_summary", historySummary);
        appendRecentOutputs(rawOutputs, compactedOutputs);

        int compressedChars = calculateContextUnits(compactedOutputs, guard);
        guard.markHistoryCompacted();
        guard.updateHistorySnapshot(originalChars, compressedChars, historySummary);
        return ContextGuardResultVO.builder()
                .stepOutputs(compactedOutputs)
                .compressed(true)
                .originalChars(originalChars)
                .compressedChars(compressedChars)
                .historySummary(historySummary)
                .build();
    }

    private void appendRecentOutputs(Map<String, String> rawOutputs, Map<String, String> compactedOutputs) {
        int keepCount = 0;
        String[] stepIds = rawOutputs.keySet().toArray(new String[0]);
        for (int i = stepIds.length - 1; i >= 0 && keepCount < 2; i--) {
            String stepId = stepIds[i];
            String output = rawOutputs.get(stepId);
            compactedOutputs.put(stepId, limit(output, 400));
            keepCount++;
        }
    }

    private String buildHistorySummary(Map<String, String> stepOutputs, int maxChars) {
        StringBuilder builder = new StringBuilder();
        builder.append("以下为历史步骤摘要：").append(System.lineSeparator());
        for (Map.Entry<String, String> entry : stepOutputs.entrySet()) {
            builder.append("- ")
                    .append(entry.getKey())
                    .append("：")
                    .append(limit(entry.getValue(), 220))
                    .append(System.lineSeparator());
            if (builder.length() >= maxChars) {
                break;
            }
        }
        String summary = builder.toString().trim();
        return summary.length() <= maxChars ? summary : summary.substring(0, maxChars) + "...";
    }

    private int calculateContextUnits(Map<String, String> stepOutputs, ContextWindowGuardVO guard) {
        int total = 0;
        for (Map.Entry<String, String> entry : stepOutputs.entrySet()) {
            total += guard.estimate(entry.getKey());
            total += guard.estimate(entry.getValue());
        }
        return total;
    }

    private String limit(String text, int maxChars) {
        return cn.ethan.ai.types.util.TextUtils.limitClean(text, maxChars);
    }

}
