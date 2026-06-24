package cn.ethan.ai.test.dao;

import cn.ethan.ai.infrastructure.dao.IAiAgentConversationMessageDao;
import cn.ethan.ai.infrastructure.dao.po.AiAgentConversationMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@SpringBootTest
@Transactional
@Rollback
public class AiAgentConversationMessageDaoIT {

    @Resource
    private IAiAgentConversationMessageDao dao;

    @Test
    public void test_insertAndQueryCompleteTurns() {
        String sessionId = "session-memory-" + System.currentTimeMillis();
        dao.insert(message(sessionId, "run-1", "USER", "第一个问题"));
        dao.insert(message(sessionId, "run-1", "ASSISTANT", "第一个回答"));
        dao.insert(message(sessionId, "run-2", "USER", "第二个问题"));

        List<AiAgentConversationMessage> recent = dao.queryCompleteTurnMessages(sessionId, 0L, 8);

        Assertions.assertEquals(2, recent.size());
        Assertions.assertEquals("USER", recent.get(0).getRole());
        Assertions.assertEquals("第一个回答", recent.get(1).getContent());
        Assertions.assertTrue(recent.stream().noneMatch(message -> "第二个问题".equals(message.getContent())));
        log.info("session 短期记忆查询数量：{}", recent.size());
    }

    @Test
    public void test_rejectDuplicateRoleForSameRun() {
        String sessionId = "session-memory-duplicate-" + System.currentTimeMillis();
        dao.insert(message(sessionId, "run-duplicate", "USER", "第一次写入"));

        try {
            dao.insert(message(sessionId, "run-duplicate", "USER", "重复写入"));
            Assertions.fail("同一 Run 的同一角色消息不应重复写入");
        } catch (Exception e) {
            Assertions.assertNotNull(e);
        }
    }

    private AiAgentConversationMessage message(String sessionId, String runId, String role, String content) {
        return AiAgentConversationMessage.builder()
                .sessionId(sessionId)
                .runId(runId)
                .role(role)
                .content(content)
                .createTime(LocalDateTime.now())
                .build();
    }

}
