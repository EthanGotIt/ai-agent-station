package cn.ethan.infrastructure.agentscope.assembler;

import cn.ethan.core.agent.enums.OutputEventTypeEnum;
import cn.ethan.core.agent.model.OutputEventModel;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentScope 事件装配器测试：验证公开事件映射和思考内容隔离。
 *
 * @author ethan
 * @date 2026-08-06
 */
class AgentScopeEventAssemblerTest {

    private final AgentScopeEventAssembler assembler = new AgentScopeEventAssembler();

    @Test
    void exposesThinkingLifecycleButDiscardsThinkingContent() {
        OutputEventModel start = assembler.assemble(
                new ThinkingBlockStartEvent("reply-1", "block-1")
        ).orElseThrow();
        Optional<OutputEventModel> result = assembler.assemble(
                new ThinkingBlockDeltaEvent("reply-1", "block-1", "内部推理")
        );
        OutputEventModel end = assembler.assemble(
                new ThinkingBlockEndEvent("reply-1", "block-1")
        ).orElseThrow();

        assertEquals("thinking_started", start.value());
        assertTrue(result.isEmpty());
        assertEquals("thinking_completed", end.value());
    }

    @Test
    void discardsInternalHintBlocks() {
        Optional<OutputEventModel> result = assembler.assemble(
                new HintBlockEvent("reply-1", "block-1", "runtime", "内部提示")
        );

        assertTrue(result.isEmpty());
    }

    @Test
    void mapsTextModelAndToolEventsToCoreProtocol() {
        OutputEventModel text = assembler.assemble(
                new TextBlockDeltaEvent("reply-1", "block-1", "公开内容")
        ).orElseThrow();
        OutputEventModel progress = assembler.assemble(
                new ModelCallStartEvent("reply-1")
        ).orElseThrow();
        OutputEventModel tool = assembler.assemble(
                new ToolCallStartEvent("reply-1", "tool-call-1", "native_search")
        ).orElseThrow();
        OutputEventModel toolResult = assembler.assemble(
                new ToolResultEndEvent(
                        "reply-1", "tool-call-1", "native_search", ToolResultState.SUCCESS
                )
        ).orElseThrow();

        assertEquals(OutputEventTypeEnum.CONTENT, text.type());
        assertEquals("公开内容", text.value());
        assertEquals(OutputEventTypeEnum.PROGRESS, progress.type());
        assertEquals("model_call_started", progress.value());
        assertEquals(OutputEventTypeEnum.TOOL, tool.type());
        assertEquals("native_search", tool.value());
        assertEquals(OutputEventTypeEnum.TOOL, toolResult.type());
        assertEquals("native_search:SUCCESS", toolResult.value());
    }
}
