package cn.ethan.ai.test.support;

/**
 * 从 {@code .env} 读取开关的 JUnit 条件帮助类。
 *
 * <p>与 {@link DotenvExtension} 不同，这里的方法在 JUnit condition 阶段调用，
 * 因此可以让 {@code @EnabledIf} 根据 {@code .env} 中的属性决定是否启用测试。</p>
 */
public final class DotenvConditions {

    private DotenvConditions() {
    }

    /**
     * 是否启用实时模型评估。
     */
    @SuppressWarnings("unused")
    public static boolean isLiveEvaluationEnabled() {
        DotenvLoader.load();
        return "true".equalsIgnoreCase(System.getProperty("live.after-sales.evaluation.enabled", ""));
    }

    /**
     * 是否启用并发基准测试。
     */
    @SuppressWarnings("unused")
    public static boolean isBenchmarkEnabled() {
        DotenvLoader.load();
        return "true".equalsIgnoreCase(System.getProperty("after-sales.benchmark.enabled", ""));
    }
}
