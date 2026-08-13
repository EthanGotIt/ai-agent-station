package cn.ethan.controller;

import cn.ethan.core.after_sales.enums.AfterSalesCaseStatusEnum;
import cn.ethan.core.after_sales.enums.AfterSalesHandlingModeEnum;
import cn.ethan.core.after_sales.enums.RefundReasonEnum;
import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.model.AfterSalesReviewResultModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import cn.ethan.handler.AgentExceptionHandler;
import cn.ethan.infrastructure.after_sales.manager.AfterSalesReviewManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 售后审核控制器测试：验证操作员边界、分页状态、审核请求和版本协议。
 *
 * @author ethan
 * @date 2026-08-12
 */
class AfterSalesCaseControllerTest {

    private AfterSalesCaseGateway cases;
    private RefundCommandGateway refunds;
    private AfterSalesReviewManager manager;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        cases = mock(AfterSalesCaseGateway.class);
        refunds = mock(RefundCommandGateway.class);
        manager = mock(AfterSalesReviewManager.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new AfterSalesCaseController(cases, refunds, manager))
                .setControllerAdvice(new AgentExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void listRequiresOperatorAndReturnsFilteredRows() throws Exception {
        when(cases.findPage(AfterSalesCaseStatusEnum.PENDING_REVIEW, 0, 21)).thenReturn(List.of(caseModel()));
        when(refunds.findByCaseId("case-1")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/after-sales/cases?status=PENDING_REVIEW"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("请求参数不合法"));

        mockMvc.perform(get("/api/v1/after-sales/cases?status=PENDING_REVIEW")
                        .header("X-Operator-Id", "operator-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].caseId").value("case-1"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING_REVIEW"));
    }

    @Test
    void approvalPassesDecisionVersionAndOperatorToManager() throws Exception {
        AfterSalesCaseModel updated = caseModel().reviewed(
                AfterSalesCaseStatusEnum.REFUND_PROCESSING, "operator-1", "decision-1", "材料齐全",
                "refund-1", Instant.parse("2026-08-12T12:01:00Z")
        );
        RefundCommandResultModel command = new RefundCommandResultModel(
                "refund-1", "case-1", "run-1", "ORDER-SHIPPED-001", "user-1", "PENDING",
                new BigDecimal("99.00"), "CNY", "", 0, Instant.parse("2026-08-12T12:01:00Z"), null,
                "", 0, Instant.parse("2026-08-12T12:01:00Z"), Instant.parse("2026-08-12T12:01:00Z")
        );
        when(manager.review(any(), eq("operator-1"))).thenReturn(new AfterSalesReviewResultModel(updated, command));

        mockMvc.perform(post("/api/v1/after-sales/cases/case-1/review-decisions")
                        .header("X-Operator-Id", "operator-1")
                        .contentType("application/json")
                        .content("""
                                {"decisionId":"decision-1","expectedVersion":0,
                                "decision":"APPROVE","note":"材料齐全"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseModel.status").value("REFUND_PROCESSING"))
                .andExpect(jsonPath("$.caseModel.refundCommand.status").value("PENDING"));

        ArgumentCaptor<cn.ethan.core.after_sales.model.AfterSalesReviewRequestModel> request =
                ArgumentCaptor.forClass(cn.ethan.core.after_sales.model.AfterSalesReviewRequestModel.class);
        verify(manager).review(request.capture(), eq("operator-1"));
        org.junit.jupiter.api.Assertions.assertEquals(0, request.getValue().expectedVersion());
        org.junit.jupiter.api.Assertions.assertEquals("decision-1", request.getValue().decisionId());
    }

    @Test
    void rejectionWithoutNoteIsRejectedAtBoundary() throws Exception {
        mockMvc.perform(post("/api/v1/after-sales/cases/case-1/review-decisions")
                        .header("X-Operator-Id", "operator-1")
                        .contentType("application/json")
                        .content("""
                                {"decisionId":"decision-1","expectedVersion":0,"decision":"REJECT","note":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private AfterSalesCaseModel caseModel() {
        Instant now = Instant.parse("2026-08-12T12:00:00Z");
        return new AfterSalesCaseModel(
                "case-1", "run-1", "user-1", "ORDER-SHIPPED-001", RefundReasonEnum.NOT_RECEIVED,
                "物流长期未更新", AfterSalesHandlingModeEnum.MANUAL_REVIEW, AfterSalesCaseStatusEnum.PENDING_REVIEW,
                new BigDecimal("99.00"), "CNY", "", 0, now, now
        );
    }
}
