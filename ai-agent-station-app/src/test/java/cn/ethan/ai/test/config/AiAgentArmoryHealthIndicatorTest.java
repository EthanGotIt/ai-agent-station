package cn.ethan.ai.test.config;

import cn.ethan.ai.config.AiAgentArmoryHealthIndicator;
import cn.ethan.ai.config.AiAgentArmoryReadyState;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

public class AiAgentArmoryHealthIndicatorTest {

    @Test
    public void shouldReportDownBeforeArmoryReady() {
        AiAgentArmoryReadyState readyState = new AiAgentArmoryReadyState(new MockEnvironment()
                .withProperty("spring.ai.agent.auto-config.enabled", "true"));
        AiAgentArmoryHealthIndicator healthIndicator = new AiAgentArmoryHealthIndicator(readyState);

        Assert.assertEquals(Status.DOWN, healthIndicator.health().getStatus());
        Assert.assertEquals("starting", healthIndicator.health().getDetails().get("stage"));
    }

    @Test
    public void shouldReportUpAfterArmoryReady() {
        AiAgentArmoryReadyState readyState = new AiAgentArmoryReadyState(new MockEnvironment()
                .withProperty("spring.ai.agent.auto-config.enabled", "true"));
        readyState.markAssembling(List.of("2101", "2102"));
        readyState.markReady("已完成对话客户端自动装配");

        AiAgentArmoryHealthIndicator healthIndicator = new AiAgentArmoryHealthIndicator(readyState);
        Assert.assertEquals(Status.UP, healthIndicator.health().getStatus());
        Assert.assertEquals("ready", healthIndicator.health().getDetails().get("stage"));
    }
}
