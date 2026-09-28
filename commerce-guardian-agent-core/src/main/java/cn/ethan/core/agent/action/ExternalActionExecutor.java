package cn.ethan.core.agent.action;


/**
 * 类型职责：执行单个已取得租约的远程动作，并返回可分类的结果。
 *
 * @author ethan
 * @date 2026-08-19
 */
public interface ExternalActionExecutor {

    ExternalActionResult execute(ExternalActionCommandModel command);

    record ExternalActionResult(
            ExternalActionOutcomeEnum outcome,
            boolean retryable,
            String code,
            String message
    ) {
        public ExternalActionResult {
            if (outcome == null) {
                throw new IllegalArgumentException("外部动作结果类型不能为空");
            }
            code = code == null ? "" : code;
            message = message == null ? "" : message;
        }

        /** 兼容只返回成功/明确失败的既有执行器。 */
        public ExternalActionResult(boolean success, boolean retryable, String code, String message) {
            this(success ? ExternalActionOutcomeEnum.SUCCEEDED : ExternalActionOutcomeEnum.FAILED,
                    retryable, code, message);
        }

        public boolean success() {
            return outcome == ExternalActionOutcomeEnum.SUCCEEDED;
        }

        public static ExternalActionResult unknown(String code, String message) {
            return new ExternalActionResult(ExternalActionOutcomeEnum.UNKNOWN, true, code, message);
        }
    }
}
