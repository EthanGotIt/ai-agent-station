package cn.ethan.core.agent.service;

import java.util.Locale;

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
