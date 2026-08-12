package cn.ethan.infrastructure.agentscope.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可逆确认探针：仅 acceptance Profile 注册，用于验证 AgentScope ASK 完整事件链。
 *
 * @author ethan
 * @date 2026-08-10
 */
public final class ReversibleConfirmationProbeTool extends ToolBase {

    public static final String NAME = "confirmation_probe";

    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("label", Map.of("type", "string")),
            "required", List.of("label"),
            "additionalProperties", false
    );

    private final AtomicInteger executions = new AtomicInteger();

    public ReversibleConfirmationProbeTool() {
        super(ToolBase.builder()
                .name(NAME)
                .description("仅用于 acceptance 确认协议诊断。只有当前用户消息明确包含字面量 "
                        + "confirmation_probe 时才允许调用；不得用于会话偏好或任何业务请求，"
                        + "不得替代 save_session_preference。")
                .inputSchema(INPUT_SCHEMA)
                .readOnly(false)
                .concurrencySafe(true)
                .externalTool(false));
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> input,
            PermissionContextState context
    ) {
        return Mono.just(PermissionDecision.ask("acceptance confirmation probe requires user confirmation"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam parameter) {
        return Mono.fromSupplier(() -> ToolResultBlock.text(
                        "CONFIRMATION_PROBE_EXECUTED count=" + executions.incrementAndGet()
                ).withState(ToolResultState.SUCCESS)
                .withIdAndName(parameter.getToolUseBlock().getId(), NAME));
    }

    public int executions() {
        return executions.get();
    }
}
