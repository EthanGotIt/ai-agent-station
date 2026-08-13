package cn.ethan.core.after_sales.exception;

/**
 * 售后申请不存在异常：避免操作员接口把不存在记录误处理为可重试的状态冲突。
 *
 * @author ethan
 * @date 2026-08-12
 */
public final class AfterSalesCaseNotFoundException extends RuntimeException {

    public AfterSalesCaseNotFoundException(String caseId) {
        super("after-sales case not found: " + caseId);
    }
}
