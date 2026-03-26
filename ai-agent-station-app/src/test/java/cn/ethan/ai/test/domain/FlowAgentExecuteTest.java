package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.ethan.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Arrays;

/**
 * Flow流程执行策略测试类
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class FlowAgentExecuteTest {

    @Resource
    private DefaultArmoryStrategyFactory defaultArmoryStrategyFactory;

    @Resource
    private DefaultFlowAgentExecuteStrategyFactory defaultFlowAgentExecuteStrategyFactory;

    @Resource
    private ApplicationContext applicationContext;

    @Before
    public void init() throws Exception {
        StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> armoryStrategyHandler =
                defaultArmoryStrategyFactory.armoryStrategyHandler();

        String apply = armoryStrategyHandler.apply(
                ArmoryCommandEntity.builder()
                        .commandType(AiAgentEnumVO.AI_CLIENT.getCode())
                        .commandIdList(Arrays.asList("2101", "2102", "2103"))
                        .build(),
                new DefaultArmoryStrategyFactory.DynamicContext());

        ChatClient chatClient = (ChatClient) applicationContext.getBean(AiAgentEnumVO.AI_CLIENT.getBeanName("2101"));
        log.info("客户端构建:{}", chatClient);
    }

    @Test
    public void testFlowAgentExecute() throws Exception {
        StrategyHandler<ExecuteCommandEntity, DefaultFlowAgentExecuteStrategyFactory.DynamicContext, String> executeHandler
                = defaultFlowAgentExecuteStrategyFactory.armoryStrategyHandler();

        ExecuteCommandEntity executeCommandEntity = new ExecuteCommandEntity();
        executeCommandEntity.setAiAgentId("1");
        executeCommandEntity.setMessage("""
                请帮我完成以下任务：

                    1. 使用小红书 MCP 工具（rednote）搜索关键词为"Java技术栈"的笔记内容，获取搜索结果。

                    2. 对搜索结果进行整理和分析，提取其中有价值的笔记信息。

                    3. 整理完成后，使用通知服务 MCP 工具（notify）发送一条"任务完成"的通知，通知内容应包含整理结果摘要。

                    请按照以上步骤依次执行。
                """);
        executeCommandEntity.setSessionId("flow-session-id-" + System.currentTimeMillis());
        executeCommandEntity.setMaxStep(5);

        // 创建动态上下文
        DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext = new DefaultFlowAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setMaxStep(executeCommandEntity.getMaxStep());
        dynamicContext.setExecutionHistory(new StringBuilder());
        dynamicContext.setCurrentTask(executeCommandEntity.getMessage());

        String apply = executeHandler.apply(executeCommandEntity, dynamicContext);
        log.info("Flow执行结果:{}", apply);
    }

}