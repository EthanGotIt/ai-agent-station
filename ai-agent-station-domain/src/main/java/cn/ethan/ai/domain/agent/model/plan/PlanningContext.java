package cn.ethan.ai.domain.agent.model.plan;

import cn.ethan.ai.domain.agent.model.AfterSalesToolCapability;
import cn.ethan.ai.domain.agent.model.ToolEvidence;

import java.util.Map;
import java.util.Set;

/**
 * 退款规划上下文。
 *
 * <p>收集当前已掌握的全部信息、上一次工具调用的结果/错误，
 * 以及当前 RePlan 状态，作为 {@link cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent} 的输入。</p>
 *
 * @param caseId            售后 Case 标识，也是模型记忆隔离键
 * @param userId            用户标识
 * @param sessionId         业务会话标识
 * @param message           用户原始消息
 * @param orderId           订单号（可能为空）
 * @param orderStatus       订单状态（可能为空）
 * @param refundReason      退款原因（可能为空）
 * @param previousToolOutput 上一次工具调用输出（可能为空）
 * @param previousToolError 上一次工具调用错误（可能为空）
 * @param retryCount        当前重试次数
 * @param replanCount       当前 RePlan 次数
 * @param lastErrorType     上一次错误类型
 * @param lastErrorMessage  上一次错误描述
 */
public record PlanningContext(
        String caseId,
        String userId,
        String sessionId,
        String message,
        String orderId,
        String orderStatus,
        String refundReason,
        String previousToolOutput,
        String previousToolError,
        int retryCount,
        int replanCount,
        String lastErrorType,
        String lastErrorMessage,
        Map<String, ToolEvidence> evidence,
        Set<AfterSalesToolCapability> availableTools
) {

    public PlanningContext(String caseId,
                           String userId,
                           String sessionId,
                           String message,
                           String orderId,
                           String orderStatus,
                           String refundReason,
                           String previousToolOutput,
                           String previousToolError,
                           int retryCount,
                           int replanCount,
                           String lastErrorType,
                           String lastErrorMessage) {
        this(caseId, userId, sessionId, message, orderId, orderStatus, refundReason,
                previousToolOutput, previousToolError, retryCount, replanCount,
                lastErrorType, lastErrorMessage, Map.of(), Set.of(AfterSalesToolCapability.QUERY_ORDER));
    }
}
