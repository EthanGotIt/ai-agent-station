package cn.ethan.ai.test.domain;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.TokenCounter;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import cn.ethan.ai.domain.agent.service.execute.graph.GraphAgentExecuteService;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.UUID;
import java.lang.reflect.Method;

public class ReactAgentCompatibilityTest {

    @Test
    public void shouldRunReactAgentAndKeepCheckpointWithoutExternalApi() throws Exception {
        ChatModel chatModel = prompt -> new ChatResponse(List.of(
                new Generation(new AssistantMessage("graph-runtime-ok"))
        ));
        MemorySaver memorySaver = MemorySaver.builder().build();
        ReactAgent reactAgent = ReactAgent.builder()
                .name("offline-compatibility-agent")
                .description("离线验证 Spring AI Alibaba GraphRuntime 依赖兼容性")
                .instruction("直接回答用户问题。")
                .chatClient(ChatClient.builder(chatModel).build())
                .saver(memorySaver)
                .releaseThread(false)
                .build();
        RunnableConfig runnableConfig = RunnableConfig.builder()
                .threadId(UUID.randomUUID().toString())
                .build();

        AssistantMessage result = reactAgent.call("ping", runnableConfig);

        Assert.assertEquals("graph-runtime-ok", result.getText());
        Assert.assertFalse(memorySaver.list(runnableConfig).isEmpty());
    }

    @Test
    public void shouldClampApproximateTokenCounterCalibration() throws Exception {
        Method method = GraphAgentExecuteService.class.getDeclaredMethod("normalizeCharsPerToken", Integer.class);
        method.setAccessible(true);

        Assert.assertEquals(4, method.invoke(null, new Object[]{null}));
        Assert.assertEquals(1, method.invoke(null, 0));
        Assert.assertEquals(8, method.invoke(null, 20));
        Assert.assertTrue(TokenCounter.approximateMsgCounter(4)
                .countTokens(List.of(new UserMessage("12345678"))) > 0);
    }

}
