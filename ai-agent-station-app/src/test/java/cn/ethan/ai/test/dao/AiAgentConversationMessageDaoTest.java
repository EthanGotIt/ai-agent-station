package cn.ethan.ai.test.dao;

import cn.ethan.ai.infrastructure.dao.IAiAgentConversationMessageDao;
import cn.ethan.ai.infrastructure.dao.po.AiAgentConversationMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
@Transactional
@Rollback
public class AiAgentConversationMessageDaoTest {

    @Resource
    private IAiAgentConversationMessageDao dao;

    @Test
    public void test_insertAndQueryRecentBySessionId() {
        String sessionId = "session-memory-" + System.currentTimeMillis();
        dao.insert(message(sessionId, "run-1", "USER", "第一个问题"));
        dao.insert(message(sessionId, "run-1", "ASSISTANT", "第一个回答"));
        dao.insert(message(sessionId, "run-2", "USER", "第二个问题"));

        List<AiAgentConversationMessage> recent = dao.queryRecentBySessionId(sessionId, 2);

        Assert.assertEquals(2, recent.size());
        Assert.assertEquals("ASSISTANT", recent.get(0).getRole());
        Assert.assertEquals("第二个问题", recent.get(1).getContent());
        log.info("session 短期记忆查询数量：{}", recent.size());
    }

    @Test
    public void test_rejectDuplicateRoleForSameRun() {
        String sessionId = "session-memory-duplicate-" + System.currentTimeMillis();
        dao.insert(message(sessionId, "run-duplicate", "USER", "第一次写入"));

        try {
            dao.insert(message(sessionId, "run-duplicate", "USER", "重复写入"));
            Assert.fail("同一 Run 的同一角色消息不应重复写入");
        } catch (Exception e) {
            Assert.assertNotNull(e);
        }
    }

    private AiAgentConversationMessage message(String sessionId, String runId, String role, String content) {
        return AiAgentConversationMessage.builder()
                .sessionId(sessionId)
                .runId(runId)
                .role(role)
                .content(content)
                .contentSummary(content)
                .contextUnits(content.length())
                .createTime(LocalDateTime.now())
                .build();
    }

}
