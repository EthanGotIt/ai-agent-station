package cn.ethan.ai.test.config;

import cn.ethan.ai.config.AfterSalesModelConfig;
import cn.ethan.ai.domain.agent.model.plan.PlanningContext;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
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
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

import java.util.List;

/**
 * 验证售后 Agent 双模型配置：Plan/Replan 与 Execute 阶段分别绑定不同模型。
 */
public class AfterSalesModelConfigTest {

    @Test
    void planningChatClientShouldUseDeepSeekV4Pro() {
        CapturingChatModel chatModel = new CapturingChatModel();
        AfterSalesModelConfig config = new AfterSalesModelConfig();

        ChatClient client = config.afterSalesPlanningChatClient(
                chatModel, config.afterSalesSessionMemoryAdvisor(sessionService()), "deepseek-v4-pro");
        client.prompt()
                .user("规划下一步")
                .advisors(advisor -> advisor
                        .param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "case-1")
                        .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, "user-1"))
                .call()
                .content();

        OpenAiChatOptions options = (OpenAiChatOptions) chatModel.lastPrompt.getOptions();
        Assertions.assertNotNull(options);
        Assertions.assertEquals("deepseek-v4-pro", options.getModel());
    }

    @Test
    void planningMemoryShouldUseCaseIdInsteadOfCallerSessionId() {
        CapturingChatModel chatModel = new CapturingChatModel();
        AfterSalesModelConfig config = new AfterSalesModelConfig();
        SessionService sessionService = sessionService();
        ChatClient client = config.afterSalesPlanningChatClient(
                chatModel, config.afterSalesSessionMemoryAdvisor(sessionService), "deepseek-v4-pro");
        RefundPlanningAgent agent = new RefundPlanningAgent(client);

        agent.plan(new PlanningContext(
                "case-1", "user-1", "shared-session", "退款", null, null, "DAMAGED",
                null, null, 0, 0, null, null));

        Assertions.assertNotNull(sessionService.findById("case-1"));
        Assertions.assertNull(sessionService.findById("shared-session"));
    }

    private SessionService sessionService() {
        return DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build())
                .build();
    }

    private static final class CapturingChatModel implements ChatModel {

        private Prompt lastPrompt;

        @Override
        public @NonNull ChatResponse call(@NonNull Prompt prompt) {
            this.lastPrompt = prompt;
            AssistantMessage message = AssistantMessage.builder()
                    .content("{\"readyToEvaluate\":true,\"steps\":[],\"checklist\":[]}")
                    .build();
            return new ChatResponse(List.of(new Generation(message)));
        }

        @Override
        public @NonNull OpenAiChatOptions getOptions() {
            return OpenAiChatOptions.builder().model("stub").build();
        }
    }
}
