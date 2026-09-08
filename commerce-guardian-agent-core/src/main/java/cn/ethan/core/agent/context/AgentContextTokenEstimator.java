package cn.ethan.core.agent.context;

/**
 * 类型职责：统一按字符估算模型上下文和输出预算使用量，避免不同边界采用不同换算方式。
 *
 * @author ethan
 * @date 2026-09-04
 */
public final class AgentContextTokenEstimator {

    private AgentContextTokenEstimator() {
    }

    /** 按当前项目约定将一段文本估算为 token 数。 */
    public static int estimateText(String value) {
        return estimateCharacters(value == null ? 0L : value.length());
    }

    /** 将已汇总的字符数换算为 token，并在整数边界处饱和。 */
    public static int estimateCharacters(long characters) {
        if (characters <= 0L) {
            return 0;
        }
        long estimated = characters / 2L + 1L;
        return estimated >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) estimated;
    }
}
