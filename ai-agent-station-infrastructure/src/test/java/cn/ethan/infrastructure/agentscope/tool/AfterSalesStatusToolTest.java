package cn.ethan.infrastructure.agentscope.tool;

import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.model.RefundCommandModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import cn.ethan.core.agent.support.CancellationToken;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 售后状态工具测试：订单与申请单均按 RuntimeContext 的用户隔离。
 *
 * @author ethan
 * @date 2026-08-10
 */
class AfterSalesStatusToolTest {

    @Test
    void readsExistingCaseForRuntimeUserOnly() {
        AtomicReference<String> caseUserId = new AtomicReference<>();
        AtomicReference<String> refundUserId = new AtomicReference<>();
        AfterSalesCaseGateway cases = new AfterSalesCaseGateway() {
            @Override
            public Optional<AfterSalesCaseModel> findByOrder(String orderId, String userId) {
                caseUserId.set(userId);
                return Optional.empty();
            }

            @Override
            public Optional<AfterSalesCaseModel> findByWorkflowRunId(String workflowRunId) {
                return Optional.empty();
            }

            @Override
            public AfterSalesCaseModel create(AfterSalesCaseModel caseModel) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean update(AfterSalesCaseModel expected, AfterSalesCaseModel updated) {
                throw new UnsupportedOperationException();
            }
        };
        RefundCommandGateway refunds = new RefundCommandGateway() {
            @Override
            public RefundCommandResultModel create(RefundCommandModel command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<RefundCommandResultModel> findByOrder(String orderId, String userId) {
                refundUserId.set(userId);
                return Optional.empty();
            }
        };
        AfterSalesStatusTool tool = new AfterSalesStatusTool(cases, refunds);

        ToolResultBlock result = tool.callAsync(parameter("user-1")).block();
        TextBlock content = (TextBlock) result.getOutput().get(0);

        assertTrue(tool.isReadOnly());
        assertTrue(tool.isConcurrencySafe());
        assertFalse(tool.isExternalTool());
        assertEquals("user-1", caseUserId.get());
        assertEquals("user-1", refundUserId.get());
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEquals("AFTER_SALES_NOT_FOUND", content.getText());
    }

    @Test
    void convertsGatewayFailureToStableToolError() {
        AfterSalesCaseGateway failingCases = new AfterSalesCaseGateway() {
            @Override
            public Optional<AfterSalesCaseModel> findByOrder(String orderId, String userId) {
                throw new IllegalStateException("downstream failure");
            }

            @Override
            public Optional<AfterSalesCaseModel> findByWorkflowRunId(String workflowRunId) {
                return Optional.empty();
            }

            @Override
            public AfterSalesCaseModel create(AfterSalesCaseModel caseModel) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean update(AfterSalesCaseModel expected, AfterSalesCaseModel updated) {
                throw new UnsupportedOperationException();
            }
        };
        RefundCommandGateway refunds = new RefundCommandGateway() {
            @Override
            public RefundCommandResultModel create(RefundCommandModel command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<RefundCommandResultModel> findByOrder(String orderId, String userId) {
                return Optional.empty();
            }
        };
        AfterSalesStatusTool tool = new AfterSalesStatusTool(failingCases, refunds);

        ToolResultBlock result = tool.callAsync(parameter("user-1")).block();
        TextBlock content = (TextBlock) result.getOutput().get(0);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(content.getText().endsWith("AFTER_SALES_TEMPORARY_FAILURE"));
    }

    private ToolCallParam parameter(String userId) {
        RuntimeContext context = RuntimeContext.builder().userId(userId).sessionId("session-1")
                .put(CancellationToken.class, new CancellationToken()).build();
        Map<String, Object> input = Map.of("orderId", "ORDER-PAID-001");
        return ToolCallParam.builder().toolUseBlock(ToolUseBlock.builder()
                        .id("tool-call-1").name(AfterSalesStatusTool.NAME).input(input).build())
                .input(input).runtimeContext(context).build();
    }
}
