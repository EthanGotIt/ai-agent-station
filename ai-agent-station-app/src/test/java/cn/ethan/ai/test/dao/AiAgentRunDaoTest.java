package cn.ethan.ai.test.dao;

import cn.ethan.ai.infrastructure.dao.IAiAgentRunDao;
import cn.ethan.ai.infrastructure.dao.po.AiAgentRun;
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

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
@Transactional
@Rollback
public class AiAgentRunDaoTest {

    @Resource
    private IAiAgentRunDao aiAgentRunDao;

    @Test
    public void test_insertAndQueryByRunId() {
        String runId = "test-run-" + System.currentTimeMillis();
        AiAgentRun run = AiAgentRun.builder()
                .runId(runId)
                .agentId("1")
                .sessionId("session-test")
                .userMessage("hello")
                .status("RUNNING")
                .sessionContextSummary("previous session summary")
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        aiAgentRunDao.insert(run);

        AiAgentRun loaded = aiAgentRunDao.queryByRunId(runId);
        Assert.assertNotNull(loaded);
        Assert.assertEquals("RUNNING", loaded.getStatus());
        Assert.assertEquals("previous session summary", loaded.getSessionContextSummary());
        log.info("运行记录查询结果：{}", loaded);
    }
}
