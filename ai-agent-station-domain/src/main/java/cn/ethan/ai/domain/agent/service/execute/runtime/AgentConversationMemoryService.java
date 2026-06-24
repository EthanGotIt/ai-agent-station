package cn.ethan.ai.domain.agent.service.execute.runtime;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentConversationMemoryRepository;
import cn.ethan.ai.domain.agent.model.valobj.AgentConversationMessageVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentConversationSessionVO;
import cn.ethan.ai.domain.agent.model.valobj.SessionContextSnapshotVO;
import cn.ethan.ai.domain.agent.model.valobj.SessionMemorySummaryVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentConversationMessageRoleEnumVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Session 级持久化短期记忆。仅成功完整 Turn 会被重新注入 Prompt。
 */
@Slf4j
@Service
public class AgentConversationMemoryService {

    private static final int RECENT_COMPLETE_MESSAGE_LIMIT = 8;

    private static final int SUMMARY_SCAN_MESSAGE_LIMIT = 200;

    private static final int OLD_TURN_SUMMARY_THRESHOLD = 1200;

    private static final int TOTAL_CONTEXT_THRESHOLD = 2400;

    private static final int SUMMARY_ITEM_LIMIT = 8;

    private static final int SUMMARY_ITEM_CHARS = 180;

    private static final int SESSION_TTL_DAYS = 30;

    private final IAgentConversationMemoryRepository repository;

    private final SessionContextAssembler sessionContextAssembler;

    private final ObjectMapper objectMapper;

    public AgentConversationMemoryService(IAgentConversationMemoryRepository repository,
                                          SessionContextAssembler sessionContextAssembler) {
        this.repository = repository;
        this.sessionContextAssembler = sessionContextAssembler;
        this.objectMapper = new ObjectMapper();
    }

    public SessionContextSnapshotVO loadSessionContext(String sessionId) {
        String normalized = normalizeSessionId(sessionId);
        if (normalized == null) {
            return SessionContextSnapshotVO.empty();
        }
        try {
            AgentConversationSessionVO session = repository.querySession(normalized);
            long cursor = session == null || session.getSummarizedMessageId() == null ? 0L : session.getSummarizedMessageId();
            SessionMemorySummaryVO summary = parseSummary(session == null ? null : session.getSummaryJson());
            List<AgentConversationMessageVO> messages = repository.queryCompleteTurnMessages(
                    normalized, cursor, RECENT_COMPLETE_MESSAGE_LIMIT);
            return sessionContextAssembler.assemble(summary, messages);
        } catch (Exception e) {
            log.warn("加载 session 短期记忆失败，本轮降级为空上下文。sessionId：{}，原因：{}", normalized, e.getMessage());
            return SessionContextSnapshotVO.empty();
        }
    }

    public void recordUserMessage(String sessionId, String runId, String content) {
        save(sessionId, runId, AgentConversationMessageRoleEnumVO.USER, content);
    }

    public void recordAssistantMessage(String sessionId, String runId, String content) {
        String normalized = normalizeSessionId(sessionId);
        if (!save(normalized, runId, AgentConversationMessageRoleEnumVO.ASSISTANT, content)) {
            return;
        }
        refreshSessionState(normalized);
    }

    public void clearSessionMemory(String sessionId) {
        String normalized = normalizeSessionId(sessionId);
        if (normalized != null) {
            repository.deleteSessionMemory(normalized);
        }
    }

    @Scheduled(cron = "0 20 3 * * *")
    public void cleanupExpiredMemory() {
        int deleted = repository.deleteExpired(LocalDateTime.now());
        if (deleted > 0) {
            log.info("已清理过期 Session 短期记忆，sessionCount：{}", deleted);
        }
    }

    private boolean save(String sessionId,
                         String runId,
                         AgentConversationMessageRoleEnumVO role,
                         String content) {
        String normalized = normalizeSessionId(sessionId);
        if (normalized == null || StringUtils.isBlank(runId) || StringUtils.isBlank(content)) {
            return false;
        }
        try {
            repository.save(AgentConversationMessageVO.builder()
                    .sessionId(normalized)
                    .runId(runId)
                    .role(role)
                    .content(content)
                    .createTime(LocalDateTime.now())
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("写入 session 短期记忆失败，本轮继续执行。sessionId：{}，runId：{}，role：{}，原因：{}",
                    normalized, runId, role, e.getMessage());
            return false;
        }
    }

    private void refreshSessionState(String sessionId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                AgentConversationSessionVO current = repository.querySession(sessionId);
                long cursor = current == null || current.getSummarizedMessageId() == null ? 0L : current.getSummarizedMessageId();
                List<AgentConversationMessageVO> messages = repository.queryCompleteTurnMessages(
                        sessionId, cursor, SUMMARY_SCAN_MESSAGE_LIMIT);
                SessionMemorySummaryVO summary = parseSummary(current == null ? null : current.getSummaryJson());
                mergeImmediatePreferences(summary, messages);

                int totalUnits = sessionContextAssembler.estimate(renderRaw(messages))
                        + sessionContextAssembler.estimate(writeSummary(summary));
                int oldMessageCount = Math.max(0, messages.size() - RECENT_COMPLETE_MESSAGE_LIMIT);
                int oldUnits = sessionContextAssembler.estimate(renderRaw(messages.subList(0, oldMessageCount)));
                if (totalUnits >= TOTAL_CONTEXT_THRESHOLD && oldMessageCount == 0 && messages.size() > 4) {
                    oldMessageCount = messages.size() - 4;
                    oldUnits = sessionContextAssembler.estimate(renderRaw(messages.subList(0, oldMessageCount)));
                }
                long summarizedMessageId = cursor;
                if (oldMessageCount > 0 && (oldUnits >= OLD_TURN_SUMMARY_THRESHOLD || totalUnits >= TOTAL_CONTEXT_THRESHOLD)) {
                    List<AgentConversationMessageVO> older = messages.subList(0, oldMessageCount);
                    mergeUserOwnedSummary(summary, older);
                    summarizedMessageId = older.stream().map(AgentConversationMessageVO::getId)
                            .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).max().orElse(cursor);
                }

                LocalDateTime now = LocalDateTime.now();
                AgentConversationSessionVO next = AgentConversationSessionVO.builder()
                        .sessionId(sessionId)
                        .summaryJson(writeSummary(summary))
                        .summarizedMessageId(summarizedMessageId)
                        .version(current == null ? 0 : current.getVersion() + 1)
                        .expiresAt(now.plusDays(SESSION_TTL_DAYS))
                        .updateTime(now)
                        .build();
                boolean saved = current == null
                        ? repository.createSession(next)
                        : repository.updateSession(next, current.getVersion());
                if (saved) {
                    return;
                }
            } catch (Exception e) {
                if (attempt == 1) {
                    log.warn("更新 Session 摘要失败，保留消息原文并继续。sessionId：{}，原因：{}", sessionId, e.getMessage());
                }
            }
        }
    }

    private void mergeImmediatePreferences(SessionMemorySummaryVO summary, List<AgentConversationMessageVO> messages) {
        messages.stream()
                .filter(message -> message.getRole() == AgentConversationMessageRoleEnumVO.USER)
                .reduce((first, second) -> second)
                .map(AgentConversationMessageVO::getContent)
                .ifPresent(content -> {
                    Map<String, String> preferences = summary.getResponsePreferences();
                    if (containsAny(content, "用中文", "中文回答")) preferences.put("language", "zh-CN");
                    if (containsAny(content, "用英文", "英文回答")) preferences.put("language", "en");
                    if (containsAny(content, "简洁", "简短")) preferences.put("detail", "concise");
                    if (containsAny(content, "详细", "事无巨细")) preferences.put("detail", "detailed");
                    if (containsAny(content, "JSON", "json")) preferences.put("format", "json");
                    if (containsAny(content, "Markdown", "markdown")) preferences.put("format", "markdown");
                    if (containsAny(content, "列表", "分点")) preferences.put("format", "list");
                });
    }

    private void mergeUserOwnedSummary(SessionMemorySummaryVO summary, List<AgentConversationMessageVO> messages) {
        messages.stream()
                .filter(message -> message.getRole() == AgentConversationMessageRoleEnumVO.USER)
                .map(AgentConversationMessageVO::getContent)
                .filter(StringUtils::isNotBlank)
                .forEach(content -> {
                    addBounded(summary.getGoals(), content);
                    if (containsAny(content, "必须", "不要", "只允许", "限制", "需要")) {
                        addBounded(summary.getConstraints(), content);
                    }
                    if (containsAny(content, "决定", "确认", "采用", "选择")) {
                        addBounded(summary.getConfirmedDecisions(), content);
                    }
                    if (containsAny(content, "?", "？", "如何", "怎么办", "为什么")) {
                        addBounded(summary.getUnresolvedQuestions(), content);
                    }
                });
    }

    private void addBounded(List<String> values, String content) {
        String normalized = StringUtils.defaultString(content).trim().replaceAll("\\s+", " ");
        normalized = normalized.length() <= SUMMARY_ITEM_CHARS
                ? normalized : normalized.substring(0, SUMMARY_ITEM_CHARS) + "...";
        values.remove(normalized);
        values.add(normalized);
        while (values.size() > SUMMARY_ITEM_LIMIT) {
            values.remove(0);
        }
    }

    private SessionMemorySummaryVO parseSummary(String json) {
        if (StringUtils.isBlank(json)) {
            return SessionMemorySummaryVO.builder().build();
        }
        try {
            SessionMemorySummaryVO parsed = objectMapper.readValue(json, SessionMemorySummaryVO.class);
            return normalizeSummary(parsed);
        } catch (Exception e) {
            log.warn("Session summary_json 无法解析，使用空结构化摘要。原因：{}", e.getMessage());
            return SessionMemorySummaryVO.builder().build();
        }
    }

    private SessionMemorySummaryVO normalizeSummary(SessionMemorySummaryVO summary) {
        if (summary == null) {
            return SessionMemorySummaryVO.builder().build();
        }
        if (summary.getGoals() == null) summary.setGoals(new ArrayList<>());
        if (summary.getConstraints() == null) summary.setConstraints(new ArrayList<>());
        if (summary.getConfirmedDecisions() == null) summary.setConfirmedDecisions(new ArrayList<>());
        if (summary.getUnresolvedQuestions() == null) summary.setUnresolvedQuestions(new ArrayList<>());
        if (summary.getResponsePreferences() == null) summary.setResponsePreferences(new LinkedHashMap<>());
        return summary;
    }

    private String writeSummary(SessionMemorySummaryVO summary) {
        try {
            return objectMapper.writeValueAsString(normalizeSummary(summary));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String renderRaw(List<AgentConversationMessageVO> messages) {
        StringBuilder builder = new StringBuilder();
        messages.forEach(message -> builder.append(message.getRole()).append(':')
                .append(StringUtils.defaultString(message.getContent())).append('\n'));
        return builder.toString();
    }

    private boolean containsAny(String text, String... candidates) {
        String value = StringUtils.defaultString(text);
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }

    private String normalizeSessionId(String sessionId) {
        return StringUtils.trimToNull(sessionId);
    }
}
