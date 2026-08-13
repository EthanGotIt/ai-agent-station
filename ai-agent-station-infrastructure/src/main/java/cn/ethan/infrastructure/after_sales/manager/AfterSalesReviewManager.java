package cn.ethan.infrastructure.after_sales.manager;

import cn.ethan.core.after_sales.model.AfterSalesRefundRetryRequestModel;
import cn.ethan.core.after_sales.model.AfterSalesReviewRequestModel;
import cn.ethan.core.after_sales.model.AfterSalesReviewResultModel;
import cn.ethan.core.after_sales.service.AfterSalesReviewService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 售后审核事务管理器：将申请单决定与退款命令创建收敛到同一数据库事务。
 *
 * @author ethan
 * @date 2026-08-12
 */
@Component
public class AfterSalesReviewManager {

    private final AfterSalesReviewService reviews;

    public AfterSalesReviewManager(AfterSalesReviewService reviews) {
        this.reviews = reviews;
    }

    @Transactional
    public AfterSalesReviewResultModel review(AfterSalesReviewRequestModel request, String operatorId) {
        return reviews.review(request, operatorId);
    }

    @Transactional
    public AfterSalesReviewResultModel retry(AfterSalesRefundRetryRequestModel request, String operatorId) {
        return reviews.retry(request, operatorId);
    }
}
