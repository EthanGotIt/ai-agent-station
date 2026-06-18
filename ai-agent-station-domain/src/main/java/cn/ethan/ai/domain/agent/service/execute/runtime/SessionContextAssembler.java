package cn.ethan.ai.domain.agent.service.execute.runtime;

import cn.ethan.ai.domain.agent.model.valobj.AgentConversationMessageVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextUnitEstimator;
import cn.ethan.ai.domain.agent.model.valobj.HeuristicContextUnitEstimator;
import cn.ethan.ai.domain.agent.model.valobj.SessionContextSnapshotVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 将持久化 session 消息组装为受预算约束的 prompt 上下文。
 */
@Service
public class SessionContextAssembler {

    private static final int DEFAULT_MAX_CONTEXT_UNITS = 2400;

    private static final double DEFAULT_COMPACT_THRESHOLD = 0.80D;

    private static final int DEFAULT_KEEP_RECENT_MESSAGES = 4;

    private static final int DEFAULT_MESSAGE_SUMMARY_MAX_CHARS = 240;

    private final int maxContextUnits;

    private final double compactThreshold;

    private final int keepRecentMessages;

    private final int messageSummaryMaxChars;

    private final ContextUnitEstimator estimator;

    public SessionContextAssembler() {
        this(
                DEFAULT_MAX_CONTEXT_UNITS,
                DEFAULT_COMPACT_THRESHOLD,
                DEFAULT_KEEP_RECENT_MESSAGES,
                DEFAULT_MESSAGE_SUMMARY_MAX_CHARS,
                HeuristicContextUnitEstimator.INSTANCE
        );
    }

    public SessionContextAssembler(int maxContextUnits,
                                   double compactThreshold,
                                   int keepRecentMessages,
                                   int messageSummaryMaxChars,
                                   ContextUnitEstimator estimator) {
        this.maxContextUnits = maxContextUnits <= 0 ? DEFAULT_MAX_CONTEXT_UNITS : maxContextUnits;
        this.compactThreshold = compactThreshold <= 0 ? DEFAULT_COMPACT_THRESHOLD : compactThreshold;
        this.keepRecentMessages = keepRecentMessages <= 0 ? DEFAULT_KEEP_RECENT_MESSAGES : keepRecentMessages;
        this.messageSummaryMaxChars = messageSummaryMaxChars <= 0
                ? DEFAULT_MESSAGE_SUMMARY_MAX_CHARS
                : messageSummaryMaxChars;
        this.estimator = estimator == null ? HeuristicContextUnitEstimator.INSTANCE : estimator;
    }

    public SessionContextSnapshotVO assemble(List<AgentConversationMessageVO> messages) {
        List<AgentConversationMessageVO> actualMessages = messages == null ? Collections.emptyList() : messages;
        if (actualMessages.isEmpty()) {
            return SessionContextSnapshotVO.empty();
        }

        int originalContextUnits = actualMessages.stream()
                .mapToInt(this::estimateOriginalMessage)
                .sum();
        boolean compressed = originalContextUnits >= (int) Math.ceil(maxContextUnits * compactThreshold);
        int recentStart = compressed ? Math.max(0, actualMessages.size() - keepRecentMessages) : 0;
        String assembled = compressed
                ? renderCompacted(actualMessages, recentStart)
                : renderOriginalMessages(actualMessages);
        String limited = limitToBudget(assembled);

        return SessionContextSnapshotVO.builder()
                .contextSummary(limited)
                .compressed(compressed)
                .originalContextUnits(originalContextUnits)
                .assembledContextUnits(estimator.estimate(limited))
                .messageCount(actualMessages.size())
                .recentMessageCount(actualMessages.size() - recentStart)
                .build();
    }

    public String summarize(String content) {
        return limit(normalize(content), messageSummaryMaxChars);
    }

    public int estimate(String content) {
        return estimator.estimate(content);
    }

    private String renderOriginalMessages(List<AgentConversationMessageVO> messages) {
        StringBuilder builder = new StringBuilder("同一 session 最近消息：\n");
        messages.forEach(message -> builder.append(renderOriginal(message)));
        return builder.toString().trim();
    }

    private String renderCompacted(List<AgentConversationMessageVO> messages, int recentStart) {
        StringBuilder builder = new StringBuilder("同一 session 较早消息摘要：\n");
        for (int index = 0; index < recentStart; index++) {
            AgentConversationMessageVO message = messages.get(index);
            builder.append("- ")
                    .append(roleOf(message))
                    .append("：")
                    .append(StringUtils.defaultIfBlank(message.getContentSummary(), summarize(message.getContent())))
                    .append('\n');
        }
        builder.append("同一 session 最近消息原文：\n");
        for (int index = recentStart; index < messages.size(); index++) {
            builder.append(renderOriginal(messages.get(index)));
        }
        return builder.toString().trim();
    }

    private String renderOriginal(AgentConversationMessageVO message) {
        if (message == null) {
            return "";
        }
        return "- " + roleOf(message) + "：" + normalize(message.getContent()) + '\n';
    }

    private int estimateOriginalMessage(AgentConversationMessageVO message) {
        if (message == null) {
            return 0;
        }
        int contentUnits = message.getContextUnits() == null || message.getContextUnits() <= 0
                ? estimator.estimate(normalize(message.getContent()))
                : message.getContextUnits();
        return estimator.estimate("- " + roleOf(message) + "：") + contentUnits + estimator.estimate("\n");
    }

    private String roleOf(AgentConversationMessageVO message) {
        return message == null || message.getRole() == null ? "UNKNOWN" : message.getRole().name();
    }

    private String limitToBudget(String text) {
        if (estimator.estimate(text) <= maxContextUnits) {
            return text;
        }
        String suffix = "...";
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (estimator.estimate(text.substring(0, middle) + suffix) <= maxContextUnits) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return text.substring(0, low).trim() + suffix;
    }

    private String normalize(String content) {
        return StringUtils.defaultString(content).trim().replaceAll("\\s+", " ");
    }

    private String limit(String content, int maxChars) {
        return content.length() <= maxChars ? content : content.substring(0, maxChars) + "...";
    }

}
