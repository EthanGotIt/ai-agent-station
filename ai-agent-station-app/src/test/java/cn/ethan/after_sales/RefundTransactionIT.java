package cn.ethan.after_sales;

import cn.ethan.core.after_sales.enums.AfterSalesCaseStatusEnum;
import cn.ethan.core.after_sales.enums.AfterSalesHandlingModeEnum;
import cn.ethan.core.after_sales.enums.AfterSalesReviewDecisionEnum;
import cn.ethan.core.after_sales.enums.RefundCommandStatusEnum;
import cn.ethan.core.after_sales.enums.RefundReasonEnum;
import cn.ethan.core.after_sales.exception.AfterSalesCaseNotFoundException;
import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.model.AfterSalesReviewRequestModel;
import cn.ethan.core.after_sales.model.RefundCommandModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import cn.ethan.infrastructure.after_sales.manager.AfterSalesReviewManager;
import cn.ethan.infrastructure.after_sales.manager.RefundCommandSettlementManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 退款事务集成测试：用独立 H2 验证审核与结算跨两张表时的原子性。
 *
 * @author ethan
 * @date 2026-08-13
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:refund-transaction;MODE=MySQL;DATABASE_TO_UPPER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.ai.openai.api-key=test-key",
                "spring.ai.session.repository.jdbc.initialize-schema=always",
                "spring.ai.session.repository.jdbc.platform=h2",
                "DASHSCOPE_API_KEY=test-key"
        }
)
class RefundTransactionIT {

    private static final Instant CREATED_AT = Instant.parse("2026-08-12T12:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AfterSalesCaseGateway cases;

    @Autowired
    private RefundCommandGateway refunds;

    @Autowired
    private AfterSalesReviewManager reviews;

    @Autowired
    private RefundCommandSettlementManager settlements;

    @BeforeEach
    void resetRefundTables() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS DEMO_REFUND_COMMAND");
        jdbcTemplate.execute("DROP TABLE IF EXISTS DEMO_AFTER_SALES_CASE");
        jdbcTemplate.execute("""
                CREATE TABLE DEMO_AFTER_SALES_CASE (
                    CASE_ID VARCHAR(64) NOT NULL PRIMARY KEY,
                    WORKFLOW_RUN_ID VARCHAR(64) NOT NULL UNIQUE,
                    USER_ID VARCHAR(64) NOT NULL,
                    ORDER_ID VARCHAR(64) NOT NULL,
                    REQUEST_TYPE VARCHAR(32) NOT NULL,
                    REFUND_REASON VARCHAR(32) NOT NULL,
                    DESCRIPTION VARCHAR(500) NOT NULL,
                    HANDLING_MODE VARCHAR(32) NOT NULL,
                    STATUS VARCHAR(32) NOT NULL,
                    AMOUNT DECIMAL(19, 2),
                    CURRENCY VARCHAR(8) NOT NULL,
                    REFUND_ID VARCHAR(64) NOT NULL,
                    OPERATOR_ID VARCHAR(128) NOT NULL,
                    DECISION_ID VARCHAR(128) NOT NULL,
                    DECISION_NOTE VARCHAR(500) NOT NULL,
                    REVIEWED_AT TIMESTAMP,
                    FAILURE_CODE VARCHAR(64) NOT NULL,
                    VERSION BIGINT NOT NULL,
                    CREATED_AT TIMESTAMP NOT NULL,
                    UPDATED_AT TIMESTAMP NOT NULL,
                    CONSTRAINT UK_DEMO_AFTER_SALES_CASE_ORDER UNIQUE (USER_ID, ORDER_ID, REQUEST_TYPE)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE DEMO_REFUND_COMMAND (
                    REFUND_ID VARCHAR(64) NOT NULL PRIMARY KEY,
                    WORKFLOW_RUN_ID VARCHAR(64) NOT NULL UNIQUE,
                    CASE_ID VARCHAR(64) NOT NULL UNIQUE,
                    ORDER_ID VARCHAR(64) NOT NULL,
                    USER_ID VARCHAR(64) NOT NULL,
                    REFUND_REASON VARCHAR(32) NOT NULL,
                    AMOUNT DECIMAL(19, 2) NOT NULL,
                    CURRENCY VARCHAR(8) NOT NULL,
                    STATUS VARCHAR(32) NOT NULL,
                    RETRY_ID VARCHAR(128) NOT NULL,
                    ATTEMPT_COUNT INT NOT NULL,
                    NEXT_ATTEMPT_AT TIMESTAMP NOT NULL,
                    LEASE_UNTIL TIMESTAMP,
                    FAILURE_CODE VARCHAR(64) NOT NULL,
                    VERSION BIGINT NOT NULL,
                    CREATED_AT TIMESTAMP NOT NULL,
                    UPDATED_AT TIMESTAMP NOT NULL
                )
                """);
    }

    @Test
    void approvalCommitsReviewedCaseAndRefundCommandTogether() {
        cases.create(pendingCase("case-review", "run-review"));

        var result = reviews.review(new AfterSalesReviewRequestModel(
                "case-review", "decision-review", 0, AfterSalesReviewDecisionEnum.APPROVE, "材料齐全"
        ), "operator-1");

        assertEquals(AfterSalesCaseStatusEnum.REFUND_PROCESSING, result.caseModel().status());
        assertNotNull(result.refundCommand());
        assertEquals(result.refundCommand().refundId(), cases.findByCaseId("case-review").orElseThrow().refundId());
        assertEquals(RefundCommandStatusEnum.PENDING,
                refunds.findByCaseId("case-review").orElseThrow().statusEnum());
    }

    @Test
    void settlementRollsBackCommandWhenRelatedCaseCannotBeUpdated() {
        RefundCommandResultModel created = refunds.create(new RefundCommandModel(
                "run-missing", "case-missing", "ORDER-001", "user-1", RefundReasonEnum.NOT_RECEIVED,
                new BigDecimal("99.00"), "CNY", CREATED_AT
        ));
        Instant claimedAt = Instant.parse("2026-08-13T12:00:00Z");
        RefundCommandResultModel claimed = refunds.claimDue(claimedAt, claimedAt.plusSeconds(30), 1).get(0);

        assertThrows(AfterSalesCaseNotFoundException.class, () -> settlements.complete(claimed));

        RefundCommandResultModel persisted = refunds.findByCaseId(created.caseId()).orElseThrow();
        assertEquals(RefundCommandStatusEnum.PROCESSING, persisted.statusEnum());
        assertEquals(claimed.version(), persisted.version());
    }

    private AfterSalesCaseModel pendingCase(String caseId, String runId) {
        return new AfterSalesCaseModel(
                caseId, runId, "user-1", "ORDER-001", RefundReasonEnum.NOT_RECEIVED,
                "物流未送达", AfterSalesHandlingModeEnum.MANUAL_REVIEW,
                AfterSalesCaseStatusEnum.PENDING_REVIEW, new BigDecimal("99.00"), "CNY", "",
                0, CREATED_AT, CREATED_AT
        );
    }
}
