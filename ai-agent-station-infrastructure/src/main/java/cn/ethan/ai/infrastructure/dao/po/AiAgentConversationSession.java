package cn.ethan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAgentConversationSession {

    private String sessionId;

    private String summaryJson;

    private Long summarizedMessageId;

    private Integer version;

    private LocalDateTime expiresAt;

    private LocalDateTime updateTime;
}
