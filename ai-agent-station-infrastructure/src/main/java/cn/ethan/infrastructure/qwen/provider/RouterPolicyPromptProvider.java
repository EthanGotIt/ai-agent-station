package cn.ethan.infrastructure.qwen.provider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Router 策略提示词：从应用包读取经评审的路由边界，缺失时阻止应用启动。
 *
 * @author ethan
 * @date 2026-08-11
 */
public final class RouterPolicyPromptProvider {

    private static final String RESOURCE_PATH = "/prompt/agent-router-policy.md";

    private final String content;

    private RouterPolicyPromptProvider(String content) {
        this.content = content;
    }

    public static RouterPolicyPromptProvider fromClasspath() {
        try (InputStream input = RouterPolicyPromptProvider.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                throw new IllegalStateException("Router Policy resource is missing: " + RESOURCE_PATH);
            }
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (content.isBlank()) {
                throw new IllegalStateException("Router Policy resource must not be blank");
            }
            return new RouterPolicyPromptProvider(content);
        } catch (IOException exception) {
            throw new IllegalStateException("Router Policy resource cannot be read", exception);
        }
    }

    public String content() {
        return content;
    }
}
