package cn.ethan.dto;

import cn.ethan.core.agent.model.AgentMemoryOptionsModel;

/**
 * HTTP 单回合记忆开关：省略字段时继承服务端默认配置。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record AgentMemoryOptionsDto(Boolean generate, Boolean use) {

    public AgentMemoryOptionsModel toModel() {
        return new AgentMemoryOptionsModel(generate, use);
    }
}
