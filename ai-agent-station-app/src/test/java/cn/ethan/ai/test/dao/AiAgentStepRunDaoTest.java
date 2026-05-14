package cn.ethan.ai.test.dao;

import cn.ethan.ai.infrastructure.dao.IAiAgentStepRunDao;
import cn.ethan.ai.infrastructure.dao.po.AiAgentStepRun;
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
public class AiAgentStepRunDaoTest {

    @Resource
    private IAiAgentStepRunDao aiAgentStepRunDao;

    @Test
    public void test_insertAndQueryByRunId() {
        String runId = "step-run-" + System.currentTimeMillis();
        aiAgentStepRunDao.insert(AiAgentStepRun.builder()
                .runId(runId)
                .stepId("step_1")
                .stepName("测试步骤")
                .stepOrder(1)
                .stepType("LLM")
                .status("RUNNING")
                .startTime(LocalDateTime.now())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build());

        List<AiAgentStepRun> rows = aiAgentStepRunDao.queryByRunId(runId);
        Assert.assertEquals(1, rows.size());
        Assert.assertEquals("step_1", rows.get(0).getStepId());
        log.info("步骤运行记录查询结果：{}", rows.get(0));
    }
}
