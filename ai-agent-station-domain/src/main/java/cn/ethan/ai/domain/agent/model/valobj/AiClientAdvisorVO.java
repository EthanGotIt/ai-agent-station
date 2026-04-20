package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 顾问配置，值对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiClientAdvisorVO {

    /**
     * 顾问ID
     */
    private String advisorId;

    /**
     * 顾问名称
     */
    private String advisorName;

    /**
     * 顾问类型(PromptChatMemory/RagAnswer/SimpleLoggerAdvisor等)
     */
    private String advisorType;

    /**
     * 顺序号
     */
    private Integer orderNum;

    /**
     * 扩展；记忆
     */
    private ChatMemory chatMemory;

    /**
     * 扩展；rag 问答
     */
    private RagAnswer ragAnswer;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ChatMemory {
        private int maxMessages;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RagAnswer {
        @Builder.Default
        private int topK = 4;

        private String filterExpression;

        /**
         * 是否启用轻量查询改写。
         */
        @Builder.Default
        private boolean queryRewriteEnabled = true;

        /**
         * 每次 RAG 最多使用的检索 Query 数量，包含原始问题。
         */
        @Builder.Default
        private int maxRewriteQueries = 3;

        /**
         * 每一路召回的候选数量。
         */
        @Builder.Default
        private int routeTopK = 4;

        /**
         * 是否对多路召回结果进行去重。
         */
        @Builder.Default
        private boolean deduplicateEnabled = true;

        /**
         * 内容指纹长度，文档缺少 chunk 元数据时用于近似去重。
         */
        @Builder.Default
        private int contentFingerprintLength = 180;
    }

}
