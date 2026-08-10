package cn.ethan.infrastructure.agentscope.tool;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 售后规则工具测试：固定规则只能以只读、有限输出方式提供给 ReAct。
 *
 * @author ethan
 * @date 2026-08-10
 */
class AfterSalesPolicyToolTest {

    @Test
    void returnsFixedReadOnlyPolicyBoundary() {
        AfterSalesPolicyTool tool = new AfterSalesPolicyTool();
        Map<String, Object> input = Map.of();
        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id("tool-call-1").name(AfterSalesPolicyTool.NAME)
                        .input(input).build())
                .input(input).build()).block();
        TextBlock content = (TextBlock) result.getOutput().get(0);

        assertTrue(tool.isReadOnly());
        assertTrue(tool.isConcurrencySafe());
        assertFalse(tool.isExternalTool());
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertTrue(content.getText().contains("final_refund_submission_requires_workflow_question_card"));
    }
}
