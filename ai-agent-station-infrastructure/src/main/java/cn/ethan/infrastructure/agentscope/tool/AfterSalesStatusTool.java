package cn.ethan.infrastructure.agentscope.tool;

import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import cn.ethan.core.agent.support.CancellationToken;
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
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 售后状态工具：按当前用户读取售后申请及已关联退款命令的只读状态。
 *
 * @author ethan
 * @date 2026-08-10
 */
public final class AfterSalesStatusTool extends ToolBase {

    public static final String NAME = "get_after_sales_status";
    private static final Pattern ORDER_ID = Pattern.compile("(?i)^ORDER-[A-Z0-9][A-Z0-9-]{0,62}$");
    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object", "properties", Map.of("orderId", Map.of("type", "string")),
            "required", List.of("orderId"), "additionalProperties", false
    );

    private final AfterSalesCaseGateway cases;
    private final RefundCommandGateway refunds;

    public AfterSalesStatusTool(AfterSalesCaseGateway cases, RefundCommandGateway refunds) {
        super(ToolBase.builder().name(NAME)
                .description("查询当前登录用户某笔订单的售后申请与退款进度，只读且不创建申请。")
                .inputSchema(INPUT_SCHEMA).readOnly(true).concurrencySafe(true).externalTool(false));
        this.cases = cases;
        this.refunds = refunds;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        return Mono.just(orderId(input == null ? null : input.get("orderId")) == null
                ? PermissionDecision.deny("订单号格式无效") : PermissionDecision.allow("售后状态查询为只读操作"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam parameter) {
        return Mono.fromSupplier(() -> {
            RuntimeContext context = parameter.getRuntimeContext();
            CancellationToken token = context == null ? null : context.get(CancellationToken.class);
            if (token != null) {
                token.throwIfCancelled();
            }
            String userId = context == null ? null : context.getUserId();
            String orderId = orderId(parameter.getInput().get("orderId"));
            if (userId == null || userId.isBlank() || orderId == null) {
                return result(parameter, ToolResultBlock.error("AFTER_SALES_UNAVAILABLE")
                        .withState(ToolResultState.ERROR));
            }
            try {
                Optional<AfterSalesCaseModel> caseModel = cases.findByOrder(orderId, userId);
                if (caseModel.isPresent()) {
                    return result(parameter, ToolResultBlock.text(format(caseModel.orElseThrow()))
                            .withState(ToolResultState.SUCCESS));
                }
                Optional<RefundCommandResultModel> refund = refunds.findByOrder(orderId, userId);
                return result(parameter, ToolResultBlock.text(refund.map(this::formatLegacy)
                                .orElse("AFTER_SALES_NOT_FOUND"))
                        .withState(ToolResultState.SUCCESS));
            } catch (RuntimeException gatewayFailure) {
                return result(parameter, ToolResultBlock.error("AFTER_SALES_TEMPORARY_FAILURE")
                        .withState(ToolResultState.ERROR));
            }
        });
    }

    private String format(AfterSalesCaseModel caseModel) {
        return "AFTER_SALES_FOUND caseId=" + safe(caseModel.caseId())
                + " orderId=" + safe(caseModel.orderId())
                + " status=" + caseModel.status().name()
                + " handlingMode=" + caseModel.handlingMode().name()
                + " refundId=" + safe(caseModel.refundId())
                + " amount=" + (caseModel.amount() == null ? "UNKNOWN" : caseModel.amount())
                + " currency=" + safe(caseModel.currency());
    }

    private String formatLegacy(RefundCommandResultModel refund) {
        return "AFTER_SALES_LEGACY_REFUND refundId=" + safe(refund.refundId())
                + " orderId=" + safe(refund.orderId()) + " status=" + safe(refund.status());
    }

    private ToolResultBlock result(ToolCallParam parameter, ToolResultBlock result) {
        return result.withIdAndName(parameter.getToolUseBlock().getId(), NAME);
    }

    private String orderId(Object input) {
        if (!(input instanceof String value)) {
            return null;
        }
        String normalized = value.strip().toUpperCase(java.util.Locale.ROOT);
        return ORDER_ID.matcher(normalized).matches() ? normalized : null;
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return normalized.substring(0, Math.min(normalized.length(), 128));
    }
}
