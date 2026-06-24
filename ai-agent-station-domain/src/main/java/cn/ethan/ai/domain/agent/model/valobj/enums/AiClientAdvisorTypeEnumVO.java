package cn.ethan.ai.domain.agent.model.valobj.enums;

import cn.ethan.ai.domain.agent.adapter.port.IRagRetrievalPort;
import cn.ethan.ai.domain.agent.model.valobj.AiClientAdvisorVO;
import cn.ethan.ai.domain.agent.service.armory.factory.element.RagAnswerAdvisor;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * 顾问类型枚举
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum AiClientAdvisorTypeEnumVO {

    CHAT_MEMORY("ChatMemory", "上下文记忆（JDBC 持久化）", false) {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, IRagRetrievalPort ragRetrievalPort) {
            throw new UnsupportedOperationException("CHAT_MEMORY 需要通过 createAdvisor(AiClientAdvisorVO, IRagRetrievalPort, ChatMemory) 创建");
        }

        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, IRagRetrievalPort ragRetrievalPort, ChatMemory chatMemory) {
            return MessageChatMemoryAdvisor.builder(chatMemory).build();
        }
    },
    
    RAG_ANSWER("RagAnswer", "知识库", true) {
        @Override
        public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, IRagRetrievalPort ragRetrievalPort) {
            if (ragRetrievalPort == null) {
                throw new IllegalStateException("RagAnswer 顾问需要启用检索端口，请检查 ai-agent.vector-store.enabled、ES 与数据库配置");
            }
            AiClientAdvisorVO.RagAnswer ragAnswer = aiClientAdvisorVO.getRagAnswer();
            if (ragAnswer == null) {
                ragAnswer = new AiClientAdvisorVO.RagAnswer();
            }
            return new RagAnswerAdvisor(ragRetrievalPort, SearchRequest.builder()
                    .topK(ragAnswer.getTopK())
                    .filterExpression(ragAnswer.getFilterExpression())
                    .build(), ragAnswer);
        }
    }
    
    ;

    private String code;
    private String info;
    private boolean vectorStoreRequired;
    
    // 静态Map缓存，用于快速查找
    private static final Map<String, AiClientAdvisorTypeEnumVO> CODE_MAP = new HashMap<>();
    
    // 静态初始化块，在类加载时初始化Map
    static {
        for (AiClientAdvisorTypeEnumVO enumVO : values()) {
            CODE_MAP.put(enumVO.getCode(), enumVO);
        }
    }
    
    /**
     * 策略方法：创建顾问对象（无 ChatMemory 注入）
     * @param aiClientAdvisorVO 顾问配置对象
     * @param ragRetrievalPort RAG 检索端口
     * @return 顾问对象
     */
    public abstract Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, IRagRetrievalPort ragRetrievalPort);

    /**
     * 策略方法：创建顾问对象（含 ChatMemory 注入，Spring AI 2.0.0 新 API）
     */
    public Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO, IRagRetrievalPort ragRetrievalPort, ChatMemory chatMemory) {
        return createAdvisor(aiClientAdvisorVO, ragRetrievalPort);
    }
    
    /**
     * 根据code 获取枚举
     * @param code 编码
     * @return 枚举对象
     */
    public static AiClientAdvisorTypeEnumVO getByCode(String code) {
        AiClientAdvisorTypeEnumVO enumVO = CODE_MAP.get(code);
        if (enumVO == null) {
            throw new RuntimeException("不支持的顾问类型：" + code);
        }
        return enumVO;
    }

}
