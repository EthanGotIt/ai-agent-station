package cn.ethan.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 售后 Agent 模型配置。
 *
 * <p>基于 OpenAI 兼容协议（当前使用 DeepSeek），使用自动配置的 {@link ChatModel} 构造两个
 * {@link ChatClient}：规划模型负责生成退款信息收集计划；执行阶段直接使用 ChatModel。</p>
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
public class AfterSalesModelConfig {

    @Bean
    public SessionMemoryAdvisor afterSalesSessionMemoryAdvisor(SessionService sessionService) {
        return SessionMemoryAdvisor.builder(sessionService)
                .defaultUserId("after-sales-agent")
                .build();
    }

    @Bean
    public ChatClient afterSalesPlanningChatClient(
            ChatModel chatModel,
            SessionMemoryAdvisor afterSalesSessionMemoryAdvisor,
            @Value("${spring.ai.openai.chat.model:deepseek-v4-pro}") String planningModel) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(afterSalesSessionMemoryAdvisor)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(planningModel)
                        .temperature(0.0))
                .build();
    }
}
