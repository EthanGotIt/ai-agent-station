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

    @Test
    public void test_cancelRunningByRunId() {
        String runId = "step-cancel-" + System.currentTimeMillis();
        LocalDateTime startTime = LocalDateTime.now().minusSeconds(1);
        aiAgentStepRunDao.insert(step(runId, "step_running", "RUNNING", startTime));
        aiAgentStepRunDao.insert(step(runId, "step_success", "SUCCESS", startTime));

        int updated = aiAgentStepRunDao.cancelRunningByRunId(runId, "测试取消", LocalDateTime.now());

        List<AiAgentStepRun> rows = aiAgentStepRunDao.queryByRunId(runId);
        Assert.assertEquals(1, updated);
        Assert.assertEquals("CANCELLED", rows.get(0).getStatus());
        Assert.assertEquals("测试取消", rows.get(0).getErrorMessage());
        Assert.assertNotNull(rows.get(0).getEndTime());
        Assert.assertEquals("SUCCESS", rows.get(1).getStatus());
    }

    private AiAgentStepRun step(String runId, String stepId, String status, LocalDateTime startTime) {
        return AiAgentStepRun.builder()
                .runId(runId)
                .stepId(stepId)
                .stepName(stepId)
                .stepOrder("step_running".equals(stepId) ? 1 : 2)
                .stepType("LLM")
                .status(status)
                .startTime(startTime)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
    }
}
