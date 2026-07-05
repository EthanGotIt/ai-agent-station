package cn.ethan.ai.types.common.exception;

/**
 * 售后域统一异常基类。
 */
public abstract class AfterSalesException extends RuntimeException {

    public AfterSalesException(String message) {
        super(message);
    }

    public AfterSalesException(String message, Throwable cause) {
        super(message, cause);
    }
}
