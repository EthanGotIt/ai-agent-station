package cn.ethan.ai.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI Agent 装配运行态
 * 用于统一暴露自动装配是否已完成，避免 HTTP 已可用但 ChatClient 尚未注册完成。
 */
@Component
public class AiAgentArmoryReadyState {

    private final AtomicBoolean ready = new AtomicBoolean(false);

    private volatile String stage;
    private volatile String message;
    private volatile List<String> clientIds;

    public AiAgentArmoryReadyState(Environment environment) {
        boolean enabled = environment.getProperty("spring.ai.agent.auto-config.enabled", Boolean.class, false);
        if (enabled) {
            this.stage = "starting";
            this.message = "等待 AI Agent 自动装配完成";
        } else {
            this.ready.set(true);
            this.stage = "disabled";
            this.message = "AI Agent 自动装配未启用";
        }
    }

    public void markAssembling(List<String> clientIds) {
        this.ready.set(false);
        this.stage = "assembling";
        this.clientIds = clientIds;
        this.message = String.format("正在装配 AI 客户端，clientIds=%s", clientIds);
    }

    public void markSkipped(String message) {
        this.ready.set(true);
        this.stage = "skipped";
        this.message = message;
    }

    public void markReady(String message) {
        this.ready.set(true);
        this.stage = "ready";
        this.message = message;
    }

    public void markFailed(String message) {
        this.ready.set(false);
        this.stage = "failed";
        this.message = message;
    }

    public boolean isReady() {
        return ready.get();
    }

    public String getStage() {
        return stage;
    }

    public String getMessage() {
        return message;
    }

    public List<String> getClientIds() {
        return clientIds;
    }
}
