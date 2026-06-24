package cn.ethan.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

public class ConfigPlaceholderResolverTest {

    @Test
    public void resolveDatabasePlaceholderFromEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("OPENAI_API_KEY", "test-key");

        String value = ConfigPlaceholderResolver.resolve("${OPENAI_API_KEY}", environment);

        Assertions.assertEquals("test-key", value);
    }

    @Test
    public void keepPlaceholderWhenEnvironmentMissing() {
        MockEnvironment environment = new MockEnvironment();

        String value = ConfigPlaceholderResolver.resolve("${OPENAI_API_KEY}", environment);

        Assertions.assertEquals("${OPENAI_API_KEY}", value);
    }

    @Test
    public void resolvePlaceholderInsideJsonConfig() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("ES_API_KEY", "es-key");

        String value = ConfigPlaceholderResolver.resolve("{\"ES_API_KEY\":\"${ES_API_KEY}\"}", environment);

        Assertions.assertEquals("{\"ES_API_KEY\":\"es-key\"}", value);
    }

}
