package cn.ethan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Session 级短期记忆消息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAgentConversationMessage {

    private Long id;

    private String sessionId;

    private String runId;

    private String role;

    private String content;

    private LocalDateTime createTime;

}
