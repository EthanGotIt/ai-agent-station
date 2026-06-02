package cn.ethan.ai.test.domain;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.UUID;

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

}
