package cn.ethan.controller;

import cn.ethan.core.after_sales.enums.AfterSalesCaseStatusEnum;
import cn.ethan.core.after_sales.exception.AfterSalesCaseNotFoundException;
import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import cn.ethan.infrastructure.after_sales.manager.AfterSalesReviewManager;
import cn.ethan.dto.AgentAfterSalesCaseDto;
import cn.ethan.dto.AgentAfterSalesCasePageDto;
import cn.ethan.dto.AgentAfterSalesRefundRetryRequestDto;
import cn.ethan.dto.AgentAfterSalesRefundRetryResponseDto;
import cn.ethan.dto.AgentAfterSalesReviewDecisionRequestDto;
import cn.ethan.dto.AgentAfterSalesReviewDecisionResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 售后审核控制器：提供可信上游操作员调用的查询、审核与失败重试协议。
 *
 * @author ethan
 * @date 2026-08-12
 */
@RestController
@RequestMapping("/api/v1/after-sales/cases")
public final class AfterSalesCaseController {

    private final AfterSalesCaseGateway cases;
    private final RefundCommandGateway refunds;
    private final AfterSalesReviewManager reviews;

    public AfterSalesCaseController(
            AfterSalesCaseGateway cases,
            RefundCommandGateway refunds,
            AfterSalesReviewManager reviews
    ) {
        this.cases = cases;
        this.refunds = refunds;
        this.reviews = reviews;
    }

    @GetMapping
    public AgentAfterSalesCasePageDto list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "X-Operator-Id", required = false) String operatorId
    ) {
        requireOperatorId(operatorId);
        if (page < 0 || size < 1 || size > 50) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 50");
        }
        AfterSalesCaseStatusEnum filter = parseStatus(status);
        List<AfterSalesCaseModel> rows = cases.findPage(filter, page * size, size + 1);
        boolean hasNext = rows.size() > size;
        List<AgentAfterSalesCaseDto> items = rows.stream().limit(size)
                .map(caseModel -> AgentAfterSalesCaseDto.from(
                        caseModel, refunds.findByCaseId(caseModel.caseId()).orElse(null)
                )).toList();
        return new AgentAfterSalesCasePageDto(items, page, size, hasNext);
    }

    @GetMapping("/{caseId}")
    public AgentAfterSalesCaseDto detail(
            @PathVariable String caseId,
            @RequestHeader(value = "X-Operator-Id", required = false) String operatorId
    ) {
        requireOperatorId(operatorId);
        AfterSalesCaseModel caseModel = cases.findByCaseId(requireCaseId(caseId))
                .orElseThrow(() -> new AfterSalesCaseNotFoundException(caseId));
        return AgentAfterSalesCaseDto.from(caseModel, refunds.findByCaseId(caseModel.caseId()).orElse(null));
    }

    @PostMapping("/{caseId}/review-decisions")
    public ResponseEntity<AgentAfterSalesReviewDecisionResponseDto> review(
            @PathVariable String caseId,
            @Valid @RequestBody AgentAfterSalesReviewDecisionRequestDto body,
            @RequestHeader(value = "X-Operator-Id", required = false) String operatorId
    ) {
        return ResponseEntity.ok(AgentAfterSalesReviewDecisionResponseDto.from(
                reviews.review(body.toModel(requireCaseId(caseId)), requireOperatorId(operatorId))
        ));
    }

    @PostMapping("/{caseId}/refund-retries")
    public ResponseEntity<AgentAfterSalesRefundRetryResponseDto> retry(
            @PathVariable String caseId,
            @Valid @RequestBody AgentAfterSalesRefundRetryRequestDto body,
            @RequestHeader(value = "X-Operator-Id", required = false) String operatorId
    ) {
        return ResponseEntity.ok(AgentAfterSalesRefundRetryResponseDto.from(
                reviews.retry(body.toModel(requireCaseId(caseId)), requireOperatorId(operatorId))
        ));
    }

    private AfterSalesCaseStatusEnum parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AfterSalesCaseStatusEnum.valueOf(status.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("status is invalid");
        }
    }

    private String requireCaseId(String caseId) {
        if (caseId == null || caseId.isBlank() || caseId.strip().length() > 128) {
            throw new IllegalArgumentException("caseId is required and must not exceed 128 characters");
        }
        return caseId.strip();
    }

    private String requireOperatorId(String operatorId) {
        if (operatorId == null || operatorId.isBlank() || operatorId.strip().length() > 128) {
            throw new IllegalArgumentException("X-Operator-Id is required and must not exceed 128 characters");
        }
        return operatorId.strip();
    }
}
