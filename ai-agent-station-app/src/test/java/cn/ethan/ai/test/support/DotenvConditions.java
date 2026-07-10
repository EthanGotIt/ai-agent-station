package cn.ethan.ai.test.support;

/**
 * 显式 Maven 开关的 JUnit 条件帮助类。
 *
 * <p>条件在 {@link DotenvExtension} 前执行，避免本地 {@code .env} 意外启用外部模型或基准测试。</p>
 */
public final class DotenvConditions {

    private DotenvConditions() {
    }

    /**
     * 是否启用实时模型评估。
     */
    @SuppressWarnings("unused")
    public static boolean isLiveEvaluationEnabled() {
        return "true".equalsIgnoreCase(System.getProperty("live.after-sales.evaluation.enabled", ""));
    }

    /**
     * 是否启用并发基准测试。
     */
    @SuppressWarnings("unused")
    public static boolean isBenchmarkEnabled() {
        return "true".equalsIgnoreCase(System.getProperty("after-sales.benchmark.enabled", ""));
    }
}
