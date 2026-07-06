package cn.ethan.ai.test.evaluation;

import cn.ethan.ai.test.support.DotenvExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
    @Qualifier("afterSalesExecutionChatClient")
    private ChatClient executionChatClient;

    @Test
    void shouldEchoSimpleMessage() {
        String content = executionChatClient.prompt()
                .user("回复一个字的问候：好")
                .call()
                .content();
        Assertions.assertNotNull(content);
        Assertions.assertFalse(content.isBlank());
        System.out.println("Model smoke response: " + content);
    }
}
