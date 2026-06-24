package cn.ethan.ai.domain.agent.model.valobj;

import cn.ethan.ai.domain.agent.model.valobj.enums.AgentConversationMessageRoleEnumVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Session 级短期记忆消息。只保存用户输入和最终回答，不保存内部 Agent prompt。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentConversationMessageVO {

    private Long id;

    private String sessionId;

    private String runId;

    private AgentConversationMessageRoleEnumVO role;

    private String content;

    private LocalDateTime createTime;

}
