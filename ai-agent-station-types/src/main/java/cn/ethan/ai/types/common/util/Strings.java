package cn.ethan.ai.types.common.util;

/**
 * 小型字符串工具类，避免各模块重复定义 isBlank / nullToEmpty 等帮助方法。
 */
public final class Strings {

    private Strings() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
