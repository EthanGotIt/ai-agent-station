package cn.ethan.ai.test.infrastructure;

import cn.ethan.ai.infrastructure.adapter.port.AgentRuntimeAdvisorPolicy;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

import java.util.List;

public class AgentRuntimeAdvisorPolicyTest {

    @Test
    public void shouldRemoveChatMemoryAdvisorFromInternalRuntimeCalls() {
        Advisor chatMemoryAdvisor = MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder().build()
        ).build();

        List<Advisor> filtered = AgentRuntimeAdvisorPolicy.filterInternalRuntimeAdvisors(List.of(chatMemoryAdvisor));

        Assert.assertTrue(filtered.isEmpty());
    }

}
