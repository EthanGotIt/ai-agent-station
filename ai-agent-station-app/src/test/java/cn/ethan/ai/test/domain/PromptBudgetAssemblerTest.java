package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.valobj.HeuristicContextUnitEstimator;
import cn.ethan.ai.domain.agent.service.execute.runtime.PromptBudgetAssembler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class PromptBudgetAssemblerTest {

    @Test
    public void shouldDropLowPriorityObservationBeforeRequiredEvidence() {
        PromptBudgetAssembler assembler = new PromptBudgetAssembler(80, HeuristicContextUnitEstimator.INSTANCE);
        String prompt = assembler.assemble(List.of(
                PromptBudgetAssembler.Section.required(10, "当前问题", "问题"),
                PromptBudgetAssembler.Section.required(20, "证据", "证据".repeat(20)),
                PromptBudgetAssembler.Section.optional(50, "observation", "低优先级".repeat(40))
        ));

        Assertions.assertTrue(prompt.contains("当前问题"));
        Assertions.assertTrue(prompt.contains("证据"));
        Assertions.assertFalse(prompt.contains("observation"));
        Assertions.assertTrue(assembler.estimate(prompt) <= 80);
    }
}
