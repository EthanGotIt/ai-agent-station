package cn.ethan.ai.test.domain;

import cn.ethan.ai.test.support.ManualTestGate;
import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.ethan.ai.domain.agent.model.valobj.ArmoryAssemblyContextVO;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.List;

@Slf4j
@SpringBootTest
public class AgentIT {

    @Resource
    private DefaultArmoryStrategyFactory defaultArmoryStrategyFactory;

    @Resource
    private ApplicationContext applicationContext;

    @Test
    public void test_aiClientApiNode() throws Exception {
        ManualTestGate.requireRealAi("AgentTest.test_aiClientApiNode");

        StrategyHandler<ArmoryCommandEntity, ArmoryAssemblyContextVO, String> armoryStrategyHandler =
                defaultArmoryStrategyFactory.armoryStrategyHandler();

        String apply = armoryStrategyHandler.apply(
                ArmoryCommandEntity.builder()
                        .commandType(AiAgentEnumVO.AI_CLIENT.getCode())
                        .commandIdList(List.of("client-advisor-main"))
                        .build(),
                new ArmoryAssemblyContextVO());

        OpenAiChatOptions apiOptions = (OpenAiChatOptions) applicationContext.getBean(AiAgentEnumVO.AI_CLIENT_API.getBeanName("api-dashscope-openai"));

        log.info("测试结果：baseUrl={}", apiOptions.getBaseUrl());
    }

    @Test
    public void test_aiClientModelNode() throws Exception {
        ManualTestGate.requireRealAi("AgentTest.test_aiClientModelNode");

        StrategyHandler<ArmoryCommandEntity, ArmoryAssemblyContextVO, String> armoryStrategyHandler =
                defaultArmoryStrategyFactory.armoryStrategyHandler();

        String apply = armoryStrategyHandler.apply(
                ArmoryCommandEntity.builder()
                        .commandType(AiAgentEnumVO.AI_CLIENT.getCode())
                        .commandIdList(List.of("client-advisor-main"))
                        .build(),
                new ArmoryAssemblyContextVO());

        OpenAiChatModel openAiChatModel = (OpenAiChatModel) applicationContext.getBean(AiAgentEnumVO.AI_CLIENT_MODEL.getBeanName("model-qwen37-max"));

        log.info("模型构建:{}", openAiChatModel);

        Prompt prompt = Prompt.builder()
                .messages(new UserMessage(
                        """
                                请用一句话说明当前模型调用是否可用。
                                """))
                .build();

        ChatResponse chatResponse = openAiChatModel.call(prompt);

        log.info("测试结果(call):{}", JSON.toJSONString(chatResponse));
    }

    @Test
    public void test_aiClientNode() throws Exception {
        ManualTestGate.requireRealAi("AgentTest.test_aiClientNode");

        StrategyHandler<ArmoryCommandEntity, ArmoryAssemblyContextVO, String> armoryStrategyHandler =
                defaultArmoryStrategyFactory.armoryStrategyHandler();

        String apply = armoryStrategyHandler.apply(
                ArmoryCommandEntity.builder()
                        .commandType(AiAgentEnumVO.AI_CLIENT.getCode())
                        .commandIdList(List.of("client-advisor-main"))
                        .build(),
                new ArmoryAssemblyContextVO());

        ChatClient chatClient = (ChatClient) applicationContext.getBean(AiAgentEnumVO.AI_CLIENT.getBeanName("client-advisor-main"));
        log.info("客户端构建:{}", chatClient);

        String content = chatClient.prompt(Prompt.builder()
                .messages(new UserMessage(
                        """
                                你有哪些工具可以使用？
                                """))
                .build()).call().content();

        log.info("测试结果(call):{}", content);
    }


}
