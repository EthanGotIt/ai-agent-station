package cn.ethan.core.agent.model;

import java.util.concurrent.CompletableFuture;

/**
 * 排队执行模型：关联请求句柄和最终执行结果。
 *
 * @author ethan
 * @date 2026-08-06
 */
public record QueuedExecutionModel(
        RequestHandleModel handle,
        CompletableFuture<AgentResponseModel> completion
) {
}
