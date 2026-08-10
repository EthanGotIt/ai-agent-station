package cn.ethan.infrastructure.agentscope.assembler;

import cn.ethan.core.agent.enums.OutputEventTypeEnum;
import cn.ethan.core.agent.model.OutputEventModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;

import java.util.Optional;

/**
 * AgentScope 事件装配器：将可公开事件转换为项目统一输出事件。
 *
 * @author ethan
 * @date 2026-08-06
 */
public final class AgentScopeEventAssembler {

    public Optional<OutputEventModel> assemble(AgentEvent event) {
        if (event instanceof ThinkingBlockStartEvent) {
            return output(OutputEventTypeEnum.PROGRESS, "thinking_started");
        }
        if (event instanceof ThinkingBlockEndEvent) {
            return output(OutputEventTypeEnum.PROGRESS, "thinking_completed");
        }
        if (event instanceof ThinkingBlockDeltaEvent || event instanceof HintBlockEvent) {
            // 思考增量和提示块可能含内部推理或运行时提示，不能越过输出边界。
            return Optional.empty();
        }
        if (event instanceof TextBlockDeltaEvent textDelta) {
            return output(OutputEventTypeEnum.CONTENT, textDelta.getDelta());
        }
        if (event instanceof ModelCallStartEvent) {
            return output(OutputEventTypeEnum.PROGRESS, "model_call_started");
        }
        if (event instanceof ModelCallEndEvent) {
            return output(OutputEventTypeEnum.PROGRESS, "model_call_completed");
        }
        if (event instanceof ToolCallStartEvent toolStart) {
            return output(OutputEventTypeEnum.TOOL, sanitizeToolName(toolStart.getToolCallName()));
        }
        if (event instanceof ToolResultEndEvent toolEnd) {
            String value = sanitizeToolName(toolEnd.getToolCallName())
                    + ":" + toolEnd.getState();
            return output(OutputEventTypeEnum.TOOL, value);
        }
        return Optional.empty();
    }

    public String sanitizeToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "tool";
        }
        String sanitized = toolName.replaceAll("[^A-Za-z0-9_.-]", "_");
        return sanitized.substring(0, Math.min(sanitized.length(), 64));
    }

    private Optional<OutputEventModel> output(OutputEventTypeEnum type, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new OutputEventModel(type, value));
    }
}
