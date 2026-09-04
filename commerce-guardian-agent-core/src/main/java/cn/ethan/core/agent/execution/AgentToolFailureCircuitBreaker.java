package cn.ethan.core.agent.execution;

/**
 * 类型职责：按工具、规范化参数和稳定错误码统计连续失败，成功或不同失败会打断计数。
 *
 * @author ethan
 * @date 2026-09-04
 */
public final class AgentToolFailureCircuitBreaker {

    private final int threshold;
    private String lastSignature;
    private int consecutiveFailures;

    public AgentToolFailureCircuitBreaker(int threshold) {
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        this.threshold = threshold;
    }

    public synchronized boolean recordFailure(String tool, String arguments, String errorCode) {
        String signature = normalize(tool) + "\u0000" + normalizeArguments(arguments)
                + "\u0000" + normalize(errorCode);
        if (signature.equals(lastSignature)) {
            consecutiveFailures++;
        }
        else {
            lastSignature = signature;
            consecutiveFailures = 1;
        }
        return consecutiveFailures >= threshold;
    }

    public synchronized void recordSuccess() {
        lastSignature = null;
        consecutiveFailures = 0;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    /** 去掉 JSON 字符串外的空白，避免格式差异绕过同一失败签名。 */
    private String normalizeArguments(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        boolean quoted = false;
        boolean escaped = false;
        for (char current : value.toCharArray()) {
            if (quoted) {
                normalized.append(current);
                if (escaped) {
                    escaped = false;
                }
                else if (current == '\\') {
                    escaped = true;
                }
                else if (current == '"') {
                    quoted = false;
                }
            }
            else if (current == '"') {
                quoted = true;
                normalized.append(current);
            }
            else if (!Character.isWhitespace(current)) {
                normalized.append(current);
            }
        }
        return normalized.toString();
    }
}
