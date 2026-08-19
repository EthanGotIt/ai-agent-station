package cn.ethan.core.agent.action.port;

import cn.ethan.core.agent.action.model.ExternalActionCommandModel;

/**
 * 类型职责：执行单个已取得租约的远程动作，并返回可分类的结果。
 *
 * @author ethan
 * @date 2026-08-19
 */
public interface ExternalActionExecutor {

    ExternalActionResult execute(ExternalActionCommandModel command);

    record ExternalActionResult(boolean success, boolean retryable, String code, String message) {
        public ExternalActionResult {
            code = code == null ? "" : code;
            message = message == null ? "" : message;
        }
    }
}
