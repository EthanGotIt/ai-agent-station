package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentContextBoundaryVO;
import cn.ethan.ai.domain.agent.model.valobj.EvidenceBoardVO;
import cn.ethan.ai.domain.agent.model.valobj.HeuristicContextUnitEstimator;
import cn.ethan.ai.domain.agent.service.execute.harness.AgentActionPromptFactory;
import cn.ethan.ai.domain.agent.service.execute.runtime.PromptBudgetAssembler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class AgentActionPromptFactoryTest {

    @Test
    void shouldPreferOfficialDocsForFrameworkApis() {
        AgentActionPromptFactory factory = new AgentActionPromptFactory(
                new PromptBudgetAssembler(12000, HeuristicContextUnitEstimator.INSTANCE));
        String prompt = factory.buildActionPrompt(
                ExecuteCommandEntity.builder().message("Spring AI ChatClient 如何传递 toolContext？").build(),
                AgentContextBoundaryVO.builder().build(), List.of(), new EvidenceBoardVO(), 1, 4);

        Assertions.assertTrue(prompt.contains("API 与版本化官方说明"));
        Assertions.assertTrue(prompt.contains("不要用它替代可获得的官方文档"));
        Assertions.assertTrue(prompt.contains("项目/当前实现/本系统"));
        Assertions.assertTrue(prompt.contains("必须分别获取 PROJECT_KNOWLEDGE"));
    }
}
