package cn.ethan.ai.test.evaluation;

import cn.ethan.ai.test.support.DotenvExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "spring.ai.model.chat=openai"
})
@ActiveProfiles("dev")
@ExtendWith(DotenvExtension.class)
@EnabledIf(value = "cn.ethan.ai.test.support.DotenvConditions#isLiveEvaluationEnabled",
        disabledReason = "实时模型评估需通过 .env 开启")
public class ModelConnectionSmokeIT {

    @Autowired
    private ChatModel chatModel;

    @Value("${spring.ai.openai.chat.options.model:${spring.ai.openai.chat.model:deepseek-v4-pro}}")
    private String planningModel;

    @Test
    void shouldEchoSimpleMessage() {
        var response = chatModel.call(new Prompt(
                new UserMessage("回复一个字的问候：好"),
                OpenAiChatOptions.builder().model(planningModel).temperature(0.0).build()));
        String content = response.getResult().getOutput().getText();
        Assertions.assertNotNull(content);
        Assertions.assertFalse(content.isBlank());
        System.out.println("Model smoke response: " + content);
    }
}
