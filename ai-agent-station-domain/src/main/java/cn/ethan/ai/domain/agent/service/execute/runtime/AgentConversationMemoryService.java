package cn.ethan.ai.domain.agent.service.execute.runtime;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentConversationMemoryRepository;
import cn.ethan.ai.domain.agent.model.valobj.AgentConversationMessageVO;
import cn.ethan.ai.domain.agent.model.valobj.SessionContextSnapshotVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentConversationMessageRoleEnumVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Session 级持久化短期记忆。内部 Agent prompt 不经过该服务落库。
 */
@Slf4j
@Service
public class AgentConversationMemoryService {

    private static final int DEFAULT_RECENT_MESSAGE_LIMIT = 20;

    private final IAgentConversationMemoryRepository repository;

    private final SessionContextAssembler sessionContextAssembler;

    public AgentConversationMemoryService(IAgentConversationMemoryRepository repository,
                                          SessionContextAssembler sessionContextAssembler) {
        this.repository = repository;
        this.sessionContextAssembler = sessionContextAssembler;
    }

    public SessionContextSnapshotVO loadSessionContext(String sessionId) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (normalizedSessionId == null) {
            return SessionContextSnapshotVO.empty();
        }
        try {
            SessionContextSnapshotVO snapshot = sessionContextAssembler.assemble(
                    repository.queryRecentMessages(normalizedSessionId, DEFAULT_RECENT_MESSAGE_LIMIT)
            );
            if (snapshot.isCompressed()) {
                log.info("session 短期记忆已压缩。sessionId：{}，messageCount：{}，recentMessageCount：{}，originalContextUnits：{}，assembledContextUnits：{}",
                        normalizedSessionId,
                        snapshot.getMessageCount(),
                        snapshot.getRecentMessageCount(),
                        snapshot.getOriginalContextUnits(),
                        snapshot.getAssembledContextUnits());
            }
            return snapshot;
        } catch (Exception e) {
            log.warn("加载 session 短期记忆失败，本轮降级为空上下文。sessionId：{}，原因：{}", normalizedSessionId, e.getMessage());
            return SessionContextSnapshotVO.empty();
        }
    }

    public void recordUserMessage(String sessionId, String runId, String content) {
        save(sessionId, runId, AgentConversationMessageRoleEnumVO.USER, content);
    }

    public void recordAssistantMessage(String sessionId, String runId, String content) {
        save(sessionId, runId, AgentConversationMessageRoleEnumVO.ASSISTANT, content);
    }

    private void save(String sessionId,
                      String runId,
                      AgentConversationMessageRoleEnumVO role,
                      String content) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (normalizedSessionId == null || StringUtils.isBlank(runId) || StringUtils.isBlank(content)) {
            return;
        }
        try {
            repository.save(AgentConversationMessageVO.builder()
                    .sessionId(normalizedSessionId)
                    .runId(runId)
                    .role(role)
                    .content(content)
                    .contentSummary(sessionContextAssembler.summarize(content))
                    .contextUnits(sessionContextAssembler.estimate(content))
                    .createTime(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("写入 session 短期记忆失败，本轮继续执行。sessionId：{}，runId：{}，role：{}，原因：{}",
                    normalizedSessionId, runId, role, e.getMessage());
        }
    }

    private String normalizeSessionId(String sessionId) {
        return StringUtils.trimToNull(sessionId);
    }

}
