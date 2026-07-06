package cn.ethan.ai.test.config;

import cn.ethan.ai.config.AfterSalesModelConfig;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

/**
 * 验证售后 Agent 双模型配置：Plan/Replan 与 Execute 阶段分别绑定不同模型。
 */
public class AfterSalesModelConfigTest {

    @Test
    void planningChatClientShouldUseDeepSeekV4Pro() {
        CapturingChatModel chatModel = new CapturingChatModel();
        AfterSalesModelConfig config = new AfterSalesModelConfig();

        ChatClient client = config.afterSalesPlanningChatClient(chatModel, "deepseek-v4-pro");
        client.prompt().user("规划下一步").call().content();

        OpenAiChatOptions options = (OpenAiChatOptions) chatModel.lastPrompt.getOptions();
        Assertions.assertNotNull(options);
        Assertions.assertEquals("deepseek-v4-pro", options.getModel());
    }

    @Test
    void executionChatClientShouldUseDeepSeekV4Flash() {
        CapturingChatModel chatModel = new CapturingChatModel();
        AfterSalesModelConfig config = new AfterSalesModelConfig();

        ChatClient client = config.afterSalesExecutionChatClient(chatModel, "deepseek-v4-flash");
        client.prompt().user("解析订单号").call().content();

        OpenAiChatOptions options = (OpenAiChatOptions) chatModel.lastPrompt.getOptions();
        Assertions.assertNotNull(options);
        Assertions.assertEquals("deepseek-v4-flash", options.getModel());
    }

    private static final class CapturingChatModel implements ChatModel {

        private Prompt lastPrompt;

        @Override
        public @NonNull ChatResponse call(@NonNull Prompt prompt) {
            this.lastPrompt = prompt;
            AssistantMessage message = AssistantMessage.builder().content("OK").build();
            return new ChatResponse(List.of(new Generation(message)));
        }

        @Override
        public @NonNull OpenAiChatOptions getOptions() {
            return OpenAiChatOptions.builder().model("stub").build();
        }
    }
}
