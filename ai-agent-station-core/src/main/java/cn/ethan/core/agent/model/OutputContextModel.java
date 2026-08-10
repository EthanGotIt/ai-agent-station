package cn.ethan.core.agent.model;

import java.time.Instant;

/**
 * 输出上下文模型：关联请求标识和输出生命周期起始时间。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record OutputContextModel(String requestId, Instant startedAt) {
}
