package cn.ethan.core.agent.context;

/**
 * 类型职责：表示上下文固定水位、游标或历史页面无法证明完整性的受控失败。
 *
 * @author ethan
 * @date 2026-09-04
 */
public final class AgentContextHistoryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AgentContextHistoryException(String message) {
        super(message);
    }

    public AgentContextHistoryException(String message, Throwable cause) {
        super(message, cause);
    }

    public String code() {
        return "CONTEXT_HISTORY_INVALID";
    }
}
