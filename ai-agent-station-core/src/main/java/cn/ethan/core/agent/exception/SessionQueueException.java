package cn.ethan.core.agent.exception;

/**
 * Session 队列异常：描述队列容量耗尽或排队等待超时。
 *
 * @author ethan
 * @date 2026-08-06
 */
public final class SessionQueueException extends RuntimeException {

    private final String code;
    private final String relatedRequestId;

    public SessionQueueException(String code, String message, String relatedRequestId) {
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
