package cn.ethan.ai.test.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class AiAgentDevConfigurationTest {

    @Test
    public void shouldBindArmoryClientIdsUnderAutoConfigPrefix() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application-dev.yml"));
        Properties properties = factory.getObject();

        Assertions.assertNotNull(properties);
        Assertions.assertEquals("client-advisor-main",
                properties.getProperty("spring.ai.agent.auto-config.client-ids"));
        Assertions.assertNull(properties.getProperty("spring.ai.agent.client-ids"));
    }

    @Test
    public void knowledgeFixturesShouldDescribeCurrentAgentArchitecture() throws Exception {
        for (String resourcePath : new String[]{
                "data/spring-ai-mcp-client.md",
                        "data/rag-evidence-retrieval.md"
        }) {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            String content;
            try (InputStreamReader reader = new InputStreamReader(
                    resource.getInputStream(), StandardCharsets.UTF_8)) {
                content = FileCopyUtils.copyToString(reader);
            }
            Assertions.assertFalse(content.contains("GraphRuntime"), resourcePath);
            Assertions.assertFalse(content.contains("Flow Plan"), resourcePath);
            Assertions.assertFalse(content.contains("tool_routing"), resourcePath);
            Assertions.assertTrue(content.contains("Evidence"), resourcePath);
        }
    }
}
