package cn.ethan.core.after_sales.exception;

/**
 * 售后申请冲突异常：表示审核、重试或异步状态收敛时发现了陈旧版本或非法迁移。
 *
 * @author ethan
 * @date 2026-08-12
 */
public final class AfterSalesCaseConflictException extends RuntimeException {

    public AfterSalesCaseConflictException(String message) {
        super(message);
    }
}
