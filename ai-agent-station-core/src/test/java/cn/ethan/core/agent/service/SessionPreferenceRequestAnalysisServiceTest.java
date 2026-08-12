package cn.ethan.core.agent.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话偏好请求分析测试：验证明确偏好可规范化，歧义和非持久化表达不会触发写入。
 *
 * @author ethan
 * @date 2026-08-12
 */
class SessionPreferenceRequestAnalysisServiceTest {

    private final SessionPreferenceRequestAnalysisService service =
            new SessionPreferenceRequestAnalysisService();

    @Test
    void resolvesNaturalLanguagePreferencesInStableOrder() {
        assertEquals(
                Map.of("response.language", "en-US", "response.detail", "concise"),
                service.resolveSessionPreferences("以后请默认使用英文回答，并保持简洁；请保存这个会话偏好。")
        );
    }

    @Test
    void resolvesExplicitToolArguments() {
        assertEquals(
                Map.of("response.detail", "detailed"),
                service.resolveSessionPreferences(
                        "请严格调用 save_session_preference，将 response.detail 保存为 detailed"
                )
        );
        assertEquals(
                Map.of("response.format", "markdown"),
                service.resolveSessionPreferences(
                        "请严格调用 save_session_preference，将 response.format 保存为 markdown"
                )
        );
    }

    @Test
    void leavesAmbiguousOrNonPersistentPreferencesForClarification() {
        assertTrue(service.resolveSessionPreferences("这次用英文回答").isEmpty());
        assertTrue(service.resolveSessionPreferences("以后英文和中文都可以").isEmpty());
    }
}
