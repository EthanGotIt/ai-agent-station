package cn.ethan.ai.domain.agent.service.execute.graph;

/**
 * Tool Guard 拒绝调用时抛出的结构化异常。
 */
public class ToolGuardException extends RuntimeException {

    private final String errorType;

    public ToolGuardException(String errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public String getErrorType() {
        return errorType;
    }
}
