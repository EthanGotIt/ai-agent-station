package cn.ethan.infrastructure.agentscope.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 售后规则工具：返回项目内确定性规则边界，不联网拼接外部政策。
 *
 * @author ethan
 * @date 2026-08-10
 */
public final class AfterSalesPolicyTool extends ToolBase {

    public static final String NAME = "get_after_sales_policy";

    public AfterSalesPolicyTool() {
        super(ToolBase.builder().name(NAME)
                .description("读取本项目支持的确定性退款处理范围；不代表外部平台政策。")
                .inputSchema(Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))
                .readOnly(true).concurrencySafe(true).externalTool(false));
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        return Mono.just(PermissionDecision.allow("售后规则查询为只读操作"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam parameter) {
        return Mono.just(ToolResultBlock.text(
                        "AFTER_SALES_POLICY paid_and_full_amount=AUTO_REFUND;"
                                + " shipped=MANUAL_REVIEW; delivered_within_7_days=MANUAL_REVIEW;"
                                + " cancelled_refunded_or_overdue=REJECTED; incomplete_data=MANUAL_REVIEW;"
                                + " final_refund_submission_requires_workflow_question_card"
                ).withState(ToolResultState.SUCCESS)
                .withIdAndName(parameter.getToolUseBlock().getId(), NAME));
    }
}
