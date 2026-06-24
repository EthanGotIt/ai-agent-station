package cn.ethan.ai.domain.agent.service.execute.runtime;

import cn.ethan.ai.domain.agent.model.valobj.AgentConversationMessageVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextUnitEstimator;
import cn.ethan.ai.domain.agent.model.valobj.HeuristicContextUnitEstimator;
import cn.ethan.ai.domain.agent.model.valobj.SessionContextSnapshotVO;
import cn.ethan.ai.domain.agent.model.valobj.SessionMemorySummaryVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentConversationMessageRoleEnumVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按完整 User Turn 组装 Session 上下文，预算不足时淘汰整个旧 Turn。
 */
@Service
public class SessionContextAssembler {

    private static final int DEFAULT_MAX_CONTEXT_UNITS = 2400;

    private static final int DEFAULT_KEEP_RECENT_TURNS = 4;

    private final int maxContextUnits;

    private final int keepRecentTurns;

    private final ContextUnitEstimator estimator;

    public SessionContextAssembler() {
        this(DEFAULT_MAX_CONTEXT_UNITS, DEFAULT_KEEP_RECENT_TURNS, HeuristicContextUnitEstimator.INSTANCE);
    }

    public SessionContextAssembler(int maxContextUnits,
                                   int keepRecentTurns,
                                   ContextUnitEstimator estimator) {
        this.maxContextUnits = maxContextUnits <= 0 ? DEFAULT_MAX_CONTEXT_UNITS : maxContextUnits;
        this.keepRecentTurns = keepRecentTurns <= 0 ? DEFAULT_KEEP_RECENT_TURNS : keepRecentTurns;
        this.estimator = estimator == null ? HeuristicContextUnitEstimator.INSTANCE : estimator;
    }

    public SessionContextSnapshotVO assemble(SessionMemorySummaryVO summary,
                                             List<AgentConversationMessageVO> messages) {
        List<Turn> allTurns = completeTurns(messages);
        int originalUnits = estimator.estimate(render(summary, allTurns));
        List<Turn> selected = new ArrayList<>(allTurns.subList(
                Math.max(0, allTurns.size() - keepRecentTurns), allTurns.size()));
        boolean compacted = allTurns.size() > selected.size() || (summary != null && !summary.isEmpty());
        String assembled = render(summary, selected);
        while (!selected.isEmpty() && estimator.estimate(assembled) > maxContextUnits) {
            selected.remove(0);
            compacted = true;
            assembled = render(summary, selected);
        }
        if (estimator.estimate(assembled) > maxContextUnits) {
            assembled = "Session 摘要存在，但在当前上下文预算内无法安全注入原文 Turn。";
            compacted = true;
        }
        return SessionContextSnapshotVO.builder()
                .contextSummary(assembled)
                .compressed(compacted)
                .originalContextUnits(originalUnits)
                .assembledContextUnits(estimator.estimate(assembled))
                .messageCount(allTurns.size() * 2)
                .recentMessageCount(selected.size() * 2)
                .build();
    }

    public int estimate(String content) {
        return estimator.estimate(content);
    }

    private List<Turn> completeTurns(List<AgentConversationMessageVO> messages) {
        Map<String, List<AgentConversationMessageVO>> grouped = new LinkedHashMap<>();
        if (messages != null) {
            messages.stream().filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(message -> message.getId() == null ? Long.MAX_VALUE : message.getId()))
                    .forEach(message -> grouped.computeIfAbsent(StringUtils.defaultString(message.getRunId()), key -> new ArrayList<>())
                            .add(message));
        }
        List<Turn> turns = new ArrayList<>();
        for (List<AgentConversationMessageVO> group : grouped.values()) {
            AgentConversationMessageVO user = group.stream()
                    .filter(message -> message.getRole() == AgentConversationMessageRoleEnumVO.USER).findFirst().orElse(null);
            AgentConversationMessageVO assistant = group.stream()
                    .filter(message -> message.getRole() == AgentConversationMessageRoleEnumVO.ASSISTANT).findFirst().orElse(null);
            if (user != null && assistant != null) {
                turns.add(new Turn(user, assistant));
            }
        }
        return turns;
    }

    private String render(SessionMemorySummaryVO summary, List<Turn> turns) {
        StringBuilder builder = new StringBuilder();
        if (summary != null && !summary.isEmpty()) {
            builder.append("Session 结构化摘要：\n");
            appendList(builder, "目标", summary.getGoals());
            appendList(builder, "约束", summary.getConstraints());
            appendList(builder, "已确认决策", summary.getConfirmedDecisions());
            appendList(builder, "未解决问题", summary.getUnresolvedQuestions());
            if (!summary.getResponsePreferences().isEmpty()) {
                builder.append("- 回答偏好：").append(summary.getResponsePreferences()).append('\n');
            }
        }
        if (!turns.isEmpty()) {
            builder.append("最近完整对话 Turn：\n");
            for (Turn turn : turns) {
                builder.append("USER：").append(normalize(turn.user().getContent())).append('\n');
                builder.append("ASSISTANT：").append(normalize(turn.assistant().getContent())).append('\n');
            }
        }
        return builder.toString().trim();
    }

    private void appendList(StringBuilder builder, String label, List<String> values) {
        if (values != null && !values.isEmpty()) {
            builder.append("- ").append(label).append("：").append(values).append('\n');
        }
    }

    private String normalize(String content) {
        return StringUtils.defaultString(content).trim().replaceAll("\\s+", " ");
    }

    private record Turn(AgentConversationMessageVO user, AgentConversationMessageVO assistant) {
    }
}
