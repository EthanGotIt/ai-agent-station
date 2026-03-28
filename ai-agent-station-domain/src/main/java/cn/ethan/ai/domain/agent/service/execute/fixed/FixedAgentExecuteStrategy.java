package cn.ethan.ai.domain.agent.service.execute.fixed;

import cn.ethan.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.service.execute.IExecuteStrategy;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.time.LocalDate;
import java.util.List;

/**
 * 固定执行策略
 */
@Slf4j
@Service("fixedAgentExecuteStrategy")
public class FixedAgentExecuteStrategy implements IExecuteStrategy {

    @Resource
    private IAgentRepository repository;

    @Resource
    protected ApplicationContext applicationContext;

    public static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    public static final String CHAT_MEMORY_RETRIEVE_SIZE_KEY = "chat_memory_response_size";

    @Override
    public void execute(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception {
        List<AiAgentClientFlowConfigVO> aiAgentClientList = repository.queryAiAgentClientsByAgentId(requestParameter.getAiAgentId());
        if (aiAgentClientList == null || aiAgentClientList.isEmpty()) {
            sendStreamResult(emitter, AutoAgentExecuteResultEntity.createErrorResult("固定执行体未配置可用客户端", requestParameter.getSessionId()));
            return;
        }

        String content = "";
        int step = 1;

        for (AiAgentClientFlowConfigVO config : aiAgentClientList) {
            sendStreamResult(emitter, AutoAgentExecuteResultEntity.createExecutionSubResult(
                    step,
                    "execution_target",
                    "正在执行客户端：" + config.getClientName(),
                    requestParameter.getSessionId()
            ));

            ChatClient chatClient = getChatClientByClientId(config.getClientId());

            content = chatClient.prompt(requestParameter.getMessage() + "，" + content)
                    .system(s -> s.param("current_date", LocalDate.now().toString()))
                    .advisors(a -> a
                            .param(CHAT_MEMORY_CONVERSATION_ID_KEY, requestParameter.getSessionId())
                            .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 100))
                    .call().content();

            sendStreamResult(emitter, AutoAgentExecuteResultEntity.createExecutionResult(step, content, requestParameter.getSessionId()));
            log.info("智能体对话进行，客户端ID {}", requestParameter.getAiAgentId());
            step++;
        }

        sendStreamResult(emitter, AutoAgentExecuteResultEntity.createSummaryResult(content, requestParameter.getSessionId()));
        sendStreamResult(emitter, AutoAgentExecuteResultEntity.createCompleteResult(requestParameter.getSessionId()));
        log.info("智能体对话请求，结果 {} {}", requestParameter.getAiAgentId(), content);
    }

    private ChatClient getChatClientByClientId(String clientId) {
        return getBean(AiAgentEnumVO.AI_CLIENT.getBeanName(clientId));
    }

    @SuppressWarnings("unchecked")
    private <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

    private void sendStreamResult(ResponseBodyEmitter emitter, AutoAgentExecuteResultEntity result) throws Exception {
        emitter.send(JSON.toJSONString(result) + "\n");
    }

}
