package cn.ethan.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 售后 Agent 双模型配置：为 Plan/Replan 与 Execute 阶段分别绑定不同模型。
 *
 * <p>基于 OpenAI 兼容协议（当前使用 DeepSeek），使用自动配置的 {@link ChatModel} 构造两个
 * {@link ChatClient}：规划模型负责生成退款信息收集计划，执行模型负责工具调用与意图解析。</p>
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
public class AfterSalesModelConfig {

    @Bean
    public ChatClient afterSalesPlanningChatClient(
            ChatModel chatModel,
            @Value("${spring.ai.openai.chat.model:deepseek-v4-pro}") String planningModel) {
        return ChatClient.builder(chatModel)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(planningModel)
                        .temperature(0.0))
                .build();
    }

    @Bean
    public ChatClient afterSalesExecutionChatClient(
            ChatModel chatModel,
            @Value("${after-sales.execution-model:deepseek-v4-flash}") String executionModel) {
        return ChatClient.builder(chatModel)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(executionModel)
                        .temperature(0.0))
                .build();
    }
}
