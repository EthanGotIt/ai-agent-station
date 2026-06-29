package cn.ethan.ai.test.dao;

import cn.ethan.ai.infrastructure.dao.IAiAgentRunDao;
import cn.ethan.ai.infrastructure.dao.po.AiAgentRun;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@SpringBootTest
@Transactional
@Rollback
public class AiAgentRunDaoIT {

    @Resource
    private IAiAgentRunDao aiAgentRunDao;

    @Test
    public void test_insertAndQueryByRunId() {
        String runId = "test-run-" + System.currentTimeMillis();
        AiAgentRun run = AiAgentRun.builder()
                .runId(runId)
                .agentId("agent-java-knowledge")
                .sessionId("session-test")
                .userMessage("hello")
                .status("RUNNING")
                .sessionContextSummary("previous session summary")
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        aiAgentRunDao.insert(run);

        AiAgentRun loaded = aiAgentRunDao.queryByRunId(runId);
        Assertions.assertNotNull(loaded);
        Assertions.assertEquals("RUNNING", loaded.getStatus());
        Assertions.assertEquals("previous session summary", loaded.getSessionContextSummary());
        log.info("运行记录查询结果：{}", loaded);
    }
}
