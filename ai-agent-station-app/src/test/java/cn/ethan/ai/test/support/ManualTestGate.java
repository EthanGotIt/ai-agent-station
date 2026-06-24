package cn.ethan.ai.test.support;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assumptions;

/**
 * 手工集成测试开关。
 * 默认跳过依赖真实模型、MCP、向量库或会修改数据库数据的测试。
 */
public final class ManualTestGate {

    private static final String REAL_AI_TEST_FLAG = "RUN_REAL_AI_TESTS";
    private static final String DB_MUTATION_TEST_FLAG = "RUN_DB_MUTATION_TESTS";

    private ManualTestGate() {
    }

    public static void requireRealAi(String scenario) {
        boolean enabled = isEnabled(REAL_AI_TEST_FLAG);
        boolean hasApiKey = StringUtils.isNotBlank(readFlag("OPENAI_API_KEY"));
        Assumptions.assumeTrue(
                enabled && hasApiKey,
                scenario + " 依赖真实模型/MCP/向量库环境，默认跳过。请设置 RUN_REAL_AI_TESTS=true 且提供 OPENAI_API_KEY 后再执行。"
        );
    }

    public static void requireDbMutation(String scenario) {
        Assumptions.assumeTrue(
                isEnabled(DB_MUTATION_TEST_FLAG),
                scenario + " 会修改本地数据库数据，默认跳过。请设置 RUN_DB_MUTATION_TESTS=true 后再执行。"
        );
    }

    private static boolean isEnabled(String key) {
        return Boolean.parseBoolean(readFlag(key));
    }

    private static String readFlag(String key) {
        String propertyValue = System.getProperty(key);
        if (StringUtils.isNotBlank(propertyValue)) {
            return propertyValue;
        }
        return System.getenv(key);
    }

}
