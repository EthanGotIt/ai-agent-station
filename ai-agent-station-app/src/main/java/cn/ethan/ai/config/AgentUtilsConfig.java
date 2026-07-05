package cn.ethan.ai.config;

import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * spring-ai-agent-utils 工具 bean 配置。
 */
@Configuration
public class AgentUtilsConfig {

    @Bean
    public TodoWriteTool todoWriteTool() {
        return TodoWriteTool.builder().build();
    }
}
