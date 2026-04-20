package cn.ethan.ai.infrastructure.adapter.repository;

import org.springframework.core.env.Environment;

/**
 * 配置占位符解析器。
 */
final class ConfigPlaceholderResolver {

    private ConfigPlaceholderResolver() {
    }

    static String resolve(String value, Environment environment) {
        if (value == null || environment == null) {
            return value;
        }
        return environment.resolvePlaceholders(value);
    }

}
