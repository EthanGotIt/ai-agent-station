package cn.ethan.ai.domain.agent.exception;

import cn.ethan.ai.types.common.exception.AfterSalesException;

public class AfterSalesResumeConflictException extends AfterSalesException {

    public AfterSalesResumeConflictException(String message) {
        super(message);
    }
}
