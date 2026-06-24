package cn.ethan.ai.domain.agent.service.execute.runtime;

import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentContextBoundaryVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文边界与轻量记忆作用域服务。
 */
@Service
public class AgentContextBoundaryService {

    private static final String DEFAULT_SESSION_ID = "anonymous-session";

    private static final String PROJECT_RULE_SCOPE = "project:ai-agent-station";

    private static final List<String> PROJECT_RULES = List.of(
            "遵循本项目 Controlled Agent Harness、Tool Guard、Agentic RAG 和上下文预算策略。",
            "不得把其他 session 的会话历史、用户偏好或工具结果带入本轮执行。",
            "知识库证据不足时说明无法从当前知识库确认，不编造证据。"
    );

    private static final List<String> PREFERENCE_MARKERS = List.of(
            "偏好",
            "喜欢",
            "习惯",
            "以后",
            "下次",
            "请用中文",
            "用中文",
            "中文回答",
            "英文回答",
            "简洁",
            "详细"
    );

    public AgentContextBoundaryVO buildBoundary(ExecuteCommandEntity command, String sessionContextSummary) {
        if (command == null) {
            return buildBoundary(null, null, sessionContextSummary);
        }
        return buildBoundary(command.getSessionId(), command.getMessage(), sessionContextSummary);
    }

    public AgentContextBoundaryVO buildBoundary(String sessionId, String message, String sessionContextSummary) {
        String sessionScopeId = resolveSessionScopeId(sessionId);
        return AgentContextBoundaryVO.builder()
                .sessionId(sessionScopeId)
                .projectRuleScope(PROJECT_RULE_SCOPE)
                .userPreferenceScope("session:" + sessionScopeId + ":preferences")
                .conversationScope("session:" + sessionScopeId + ":conversation_memory")
                .projectRules(PROJECT_RULES)
                .userPreferences(extractUserPreferences(message))
                .sessionContextSummary(StringUtils.defaultString(sessionContextSummary))
                .longTermMemoryEnabled(false)
                .build();
    }

    public static String resolveSessionScopeId(String sessionId) {
        String normalized = StringUtils.trimToEmpty(sessionId);
        return StringUtils.isBlank(normalized) ? DEFAULT_SESSION_ID : normalized;
    }

    public static List<String> extractUserPreferences(String message) {
        if (StringUtils.isBlank(message)) {
            return Collections.emptyList();
        }
        String normalized = message.trim().replaceAll("\\s+", " ");
        boolean hasPreferenceMarker = PREFERENCE_MARKERS.stream().anyMatch(normalized::contains);
        if (!hasPreferenceMarker) {
            return Collections.emptyList();
        }
        return List.of(limit(normalized, 180));
    }

    public static Map<String, Object> buildPayload(AgentContextBoundaryVO boundary) {
        if (boundary == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", boundary.getSessionId());
        payload.put("projectRuleScope", boundary.getProjectRuleScope());
        payload.put("userPreferenceScope", boundary.getUserPreferenceScope());
        payload.put("conversationScope", boundary.getConversationScope());
        payload.put("projectRules", boundary.getProjectRules());
        payload.put("userPreferences", boundary.getUserPreferences());
        payload.put("sessionContextSummary", boundary.getSessionContextSummary());
        payload.put("longTermMemoryEnabled", boundary.isLongTermMemoryEnabled());
        return payload;
    }

    public static String buildPromptSection(AgentContextBoundaryVO boundary) {
        if (boundary == null) {
            return "上下文治理边界：未绑定，本轮只能使用当前请求和显式传入的步骤输出。";
        }
        return """
                上下文治理边界：
                - sessionId：%s
                - 项目规则作用域：%s
                - 用户偏好作用域：%s，仅本 session 生效，不写入长期记忆。
                - 会话上下文作用域：%s，仅本 session 的持久化短期记忆和本轮步骤输出可用。
                - 长期记忆：%s
                - 项目规则：%s
                - 本轮识别到的用户偏好：%s
                - 持久化 session 短期记忆：%s
                约束：不得跨 session 推断用户偏好或复用会话历史；不得把内部 Planner、Executor、Supervisor prompt 当作用户会话记忆。
                """.formatted(
                boundary.getSessionId(),
                boundary.getProjectRuleScope(),
                boundary.getUserPreferenceScope(),
                boundary.getConversationScope(),
                boundary.isLongTermMemoryEnabled() ? "已启用" : "未启用",
                boundary.getProjectRules(),
                boundary.getUserPreferences() == null || boundary.getUserPreferences().isEmpty()
                        ? "无"
                        : boundary.getUserPreferences(),
                StringUtils.defaultIfBlank(boundary.getSessionContextSummary(), "无")
        );
    }

    private static String limit(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "...";
    }

}
