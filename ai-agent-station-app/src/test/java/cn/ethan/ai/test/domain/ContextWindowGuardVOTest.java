package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.valobj.ContextBudgetPolicyVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextWindowGuardVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ContextWindowGuardVOTest {

    @Test
    public void shouldBudgetEachPromptIndependently() {
        ContextWindowGuardVO guard = new ContextWindowGuardVO(ContextBudgetPolicyVO.builder()
                .maxContextUnits(100).stopThreshold(0.9D).build());

        Assertions.assertTrue(guard.shouldStopNewLlmCall("甲".repeat(90)));
        Assertions.assertFalse(guard.shouldStopNewLlmCall("短提示"));
        Assertions.assertTrue(guard.getLatestInputUnits() < 90);
    }

    @Test
    public void shouldNotCountModelOutputAgainstNextPrompt() {
        ContextWindowGuardVO guard = new ContextWindowGuardVO(ContextBudgetPolicyVO.builder()
                .maxContextUnits(100).stopThreshold(0.9D).build());

        guard.inspectPrompt("上一轮长提示".repeat(20));
        Assertions.assertFalse(guard.shouldStopNewLlmCall("新提示"));
        Assertions.assertEquals(3, guard.getLatestInputUnits());
    }
}
