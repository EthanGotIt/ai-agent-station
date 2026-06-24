package cn.ethan.ai.domain.agent.service.armory;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

public class AiClientModelNodeTest {

    @Test
    void shouldClampModelRequestTimeout() {
        Assertions.assertEquals(Duration.ofSeconds(10), AiClientModelNode.resolveRequestTimeout(1));
        Assertions.assertEquals(Duration.ofSeconds(60), AiClientModelNode.resolveRequestTimeout(60));
        Assertions.assertEquals(Duration.ofSeconds(300), AiClientModelNode.resolveRequestTimeout(900));
        Assertions.assertEquals(0, AiClientModelNode.resolveMaxRetries(-1));
        Assertions.assertEquals(1, AiClientModelNode.resolveMaxRetries(1));
        Assertions.assertEquals(2, AiClientModelNode.resolveMaxRetries(9));
    }
}
