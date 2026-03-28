package cn.ethan.ai.domain.agent.service.execute.auto.step;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.DynamicContextObjectKeyEnumVO;
import cn.ethan.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.ethan.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public abstract class AbstractExecuteSupport extends AbstractMultiThreadStrategyRouter<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> {

    private final Logger log = LoggerFactory.getLogger(AbstractExecuteSupport.class);

    @Resource
    protected ApplicationContext applicationContext;

    @Resource
    protected IAgentRepository repository;

    public static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    public static final String CHAT_MEMORY_RETRIEVE_SIZE_KEY = "chat_memory_response_size";

    @Override
    protected void multiThread(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {}

    protected ChatClient getChatClientByClientId(String clientId) {
        return getBean(AiAgentEnumVO.AI_CLIENT.getBeanName(clientId));
    }

    protected ChatClient getChatClientByClientId(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, String clientId) {
        if (dynamicContext != null) {
            Map<String, ChatClient> chatClientMap = dynamicContext.getValue(DynamicContextObjectKeyEnumVO.AI_CLIENT_CHAT_CLIENT_OBJECT_MAP_KEY.getCode());
            if (chatClientMap != null) {
                ChatClient chatClient = chatClientMap.get(clientId);
                if (chatClient != null) {
                    return chatClient;
                }
            }
        }
        // 兼容：如果未从 dynamicContext 取到，则回退到 Spring 容器（旧行为）
        return getChatClientByClientId(clientId);
    }

    @SuppressWarnings("unchecked")
    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

    /**
     * 通用的结果发送方法
     * @param dynamicContext 动态上下文
     * @param result 要发送的结果实体
     */
    protected void sendStreamResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                    AutoAgentExecuteResultEntity result) {
        try {
            ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
            if (emitter != null) {
                emitter.send(encodeStreamResult(result));
            }
        } catch (IOException e) {
            log.error("发送流式结果失败：{}", e.getMessage(), e);
        }
    }

    private String encodeStreamResult(AutoAgentExecuteResultEntity result) {
        String json = JSON.toJSONString(result);
        return json + "\n";
    }
}
