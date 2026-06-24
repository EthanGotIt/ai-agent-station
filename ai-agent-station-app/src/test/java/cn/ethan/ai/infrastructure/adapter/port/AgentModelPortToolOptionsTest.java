package cn.ethan.ai.infrastructure.adapter.port;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

public class AgentModelPortToolOptionsTest {

    @Test
    void externalEvidenceMustAllowToolLoopToTerminate() {
        OpenAiChatOptions options = AgentModelPort.externalEvidenceOptions().build();

        Assertions.assertEquals("auto", options.getToolChoice());
        Assertions.assertEquals(Boolean.FALSE, options.getParallelToolCalls());
        Assertions.assertEquals(Boolean.FALSE, options.getExtraBody().get("enable_thinking"));
    }
}
