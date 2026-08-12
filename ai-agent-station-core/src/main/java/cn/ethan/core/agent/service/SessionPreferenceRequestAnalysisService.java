package cn.ethan.core.agent.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 会话偏好请求分析服务：识别用户以自然语言明确要求持久化的回答偏好。
 *
 * @author ethan
 * @date 2026-08-11
 */
public final class SessionPreferenceRequestAnalysisService {

    public boolean requiresSessionPreferenceSave(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (normalized.contains("save_session_preference")) {
            return true;
        }
        return containsPreference(normalized) && containsPersistenceIntent(normalized);
    }

    /**
     * 提取用户明确要求持久化且可无歧义规范化的会话偏好；歧义项留给 ReAct 澄清。
     */
    public Map<String, String> resolveSessionPreferences(String message) {
        if (!requiresSessionPreferenceSave(message)) {
            return Map.of();
        }
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        Map<String, String> preferences = new LinkedHashMap<>();
        resolveExclusive(
                containsAny(normalized, "en-us", "english", "英文"),
                containsAny(normalized, "zh-cn", "chinese", "中文", "简体中文"),
                "response.language", "en-US", "zh-CN", preferences
        );
        resolveExclusive(
                containsAny(normalized, "markdown", " md "),
                containsAny(normalized, "bullet_list", "bullets", "列表", "要点"),
                "response.format", "markdown", "bullet_list", preferences
        );
        if (!preferences.containsKey("response.format")
                && containsAny(normalized, "paragraph", "plain text", "段落", "纯文本")) {
            preferences.put("response.format", "paragraph");
        }
        resolveExclusive(
                containsAny(normalized, "concise", "brief", "简洁"),
                containsAny(normalized, "detailed", "详细"),
                "response.detail", "concise", "detailed", preferences
        );
        if (!preferences.containsKey("response.detail")
                && containsAny(normalized, "standard", "标准")) {
            preferences.put("response.detail", "standard");
        }
        return Collections.unmodifiableMap(preferences);
    }

    private void resolveExclusive(
            boolean first,
            boolean second,
            String key,
            String firstValue,
            String secondValue,
            Map<String, String> preferences
    ) {
        if (first == second) {
            return;
        }
        preferences.put(key, first ? firstValue : secondValue);
    }

    private boolean containsAny(String normalized, String... candidates) {
        for (String candidate : candidates) {
            if (normalized.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsPreference(String normalized) {
        return normalized.contains("语言")
                || normalized.contains("中文")
                || normalized.contains("英文")
                || normalized.contains("english")
                || normalized.contains("格式")
                || normalized.contains("markdown")
                || normalized.contains("列表")
                || normalized.contains("简洁")
                || normalized.contains("详细")
                || normalized.contains("回复风格")
                || normalized.contains("回答风格");
    }

    private boolean containsPersistenceIntent(String normalized) {
        return normalized.contains("以后")
                || normalized.contains("之后")
                || normalized.contains("今后")
                || normalized.contains("后续")
                || normalized.contains("记住")
                || normalized.contains("保存")
                || normalized.contains("固定")
                || normalized.contains("默认")
                || normalized.contains("一直")
                || normalized.contains("都用");
    }
}
