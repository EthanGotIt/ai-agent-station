package cn.ethan.infrastructure.agentscope.tool;

import cn.ethan.core.agent.enums.AgentMemoryCategoryEnum;
import cn.ethan.core.agent.service.AgentMemoryService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话偏好写入工具：经 ASK 确认后仅写入当前会话中可编辑的回答偏好。
 *
 * @author ethan
 * @date 2026-08-10
 */
public final class SaveSessionPreferenceTool extends ToolBase {

    public static final String NAME = "save_session_preference";
    private static final Set<String> KEYS = Set.of(
            "response.language", "response.format", "response.detail"
    );
    private static final Map<String, Set<String>> VALUES = Map.of(
            "response.language", Set.of("zh-CN", "en-US"),
            "response.format", Set.of("paragraph", "markdown", "bullet_list"),
            "response.detail", Set.of("concise", "standard", "detailed")
    );
    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "key", Map.of("type", "string", "enum", List.copyOf(KEYS)),
                    "value", Map.of("type", "string", "description", "必须使用工具说明中的规范化值")
            ),
            "required", List.of("key", "value"), "additionalProperties", false
    );

    private final AgentMemoryService memories;

    public SaveSessionPreferenceTool(AgentMemoryService memories) {
        super(ToolBase.builder().name(NAME)
                .description("经用户确认后保存当前会话的回答偏好。key 仅可为 response.language、response.format、response.detail；"
                        + "规范值分别为 zh-CN/en-US、paragraph/markdown/bullet_list、concise/standard/detailed。")
                .inputSchema(INPUT_SCHEMA).readOnly(false).concurrencySafe(true).externalTool(false));
        this.memories = memories;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        String key = text(input == null ? null : input.get("key"));
        String value = text(input == null ? null : input.get("value"));
        if (!valid(key, value)) {
            return Mono.just(PermissionDecision.deny("偏好键或规范化值无效"));
        }
        return Mono.just(PermissionDecision.ask(
                "将写入当前会话偏好：" + key + " = " + value + "；可稍后编辑或删除。"
        ));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam parameter) {
        return Mono.fromSupplier(() -> {
            RuntimeContext context = parameter.getRuntimeContext();
            String userId = context == null ? null : context.getUserId();
            String sessionId = context == null ? null : context.getSessionId();
            String key = text(parameter.getInput().get("key"));
            String value = text(parameter.getInput().get("value"));
            if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank() || !valid(key, value)) {
                return result(parameter, ToolResultBlock.error("PREFERENCE_UNAVAILABLE").withState(ToolResultState.ERROR));
            }
            memories.create(userId, sessionId, AgentMemoryCategoryEnum.PREFERENCE, key, value, null);
            return result(parameter, ToolResultBlock.text(
                            "PREFERENCE_SAVED key=" + key + " value=" + value + " scope=current_session"
                    ).withState(ToolResultState.SUCCESS));
        });
    }

    private ToolResultBlock result(ToolCallParam parameter, ToolResultBlock result) {
        return result.withIdAndName(parameter.getToolUseBlock().getId(), NAME);
    }

    private boolean valid(String key, String value) {
        return key != null && value != null && KEYS.contains(key) && VALUES.get(key).contains(value);
    }

    private String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }
}
