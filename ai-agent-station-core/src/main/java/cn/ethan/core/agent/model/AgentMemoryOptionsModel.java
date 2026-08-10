package cn.ethan.core.agent.model;

/**
 * 单回合记忆开关：null 表示继承服务端全局默认值。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record AgentMemoryOptionsModel(Boolean generate, Boolean use) {

    public static final AgentMemoryOptionsModel DEFAULT = new AgentMemoryOptionsModel(null, null);

    public AgentMemoryOptionsModel {
        // 允许 null，以便 HTTP 请求显式继承服务端默认配置。
    }

    public boolean generationEnabled(boolean defaultValue) {
        return generate == null ? defaultValue : generate;
    }

    public boolean usageEnabled(boolean defaultValue) {
        return use == null ? defaultValue : use;
    }
}
