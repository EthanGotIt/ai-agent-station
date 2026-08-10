package cn.ethan.core.agent.exception;

/**
 * ReAct 执行异常：以稳定错误码隔离具体 Agent 框架的异常细节。
 *
 * @author ethan
 * @date 2026-08-06
 */
public final class ReActExecutionException extends RuntimeException {

    private final String code;

    public ReActExecutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ReActExecutionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
