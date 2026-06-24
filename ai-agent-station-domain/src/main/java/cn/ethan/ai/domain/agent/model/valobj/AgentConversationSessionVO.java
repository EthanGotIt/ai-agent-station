package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Session 滚动摘要状态。消息原文仍存放在 conversation_message。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentConversationSessionVO {

    private String sessionId;

    private String summaryJson;

    private Long summarizedMessageId;

    private Integer version;

    private LocalDateTime expiresAt;

    private LocalDateTime updateTime;
}
