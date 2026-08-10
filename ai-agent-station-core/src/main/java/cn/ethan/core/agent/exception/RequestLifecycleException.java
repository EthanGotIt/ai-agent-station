package cn.ethan.core.agent.exception;

/**
 * 请求生命周期异常：描述请求标识冲突等状态冲突。
 *
 * @author ethan
 * @date 2026-08-05
 */
public final class RequestLifecycleException extends RuntimeException {

    private final String code;
    private final String relatedRequestId;

    public RequestLifecycleException(String code, String message, String relatedRequestId) {
        super(message);
        this.code = code;
        this.relatedRequestId = relatedRequestId;
    }

    public String getCode() {
        return code;
    }

    public String getRelatedRequestId() {
        return relatedRequestId;
    }
}
