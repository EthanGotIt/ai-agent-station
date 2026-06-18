package cn.ethan.ai.test.dao;

import cn.ethan.ai.test.support.ManualTestGate;
import cn.ethan.ai.infrastructure.dao.IAiAgentHarnessConfigDao;
import cn.ethan.ai.infrastructure.dao.po.AiAgentHarnessConfig;
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
public class AiAgentHarnessConfigDaoTest {

    private static final String TEST_AGENT_ID = "test-flow-dao";

    @Resource
    private IAiAgentHarnessConfigDao aiAgentHarnessConfigDao;

    @Test
    public void test_insert() {
        ManualTestGate.requireDbMutation("AiAgentHarnessConfigDaoTest.test_insert");

        AiAgentHarnessConfig aiAgentHarnessConfig = buildTestConfig(uniqueClientId(), 1);
        try {
            int result = aiAgentHarnessConfigDao.insert(aiAgentHarnessConfig);
            Assert.assertEquals(1, result);
            Assert.assertNotNull(aiAgentHarnessConfig.getId());
            log.info("插入结果: {}, 生成ID: {}", result, aiAgentHarnessConfig.getId());
        } finally {
            deleteIfCreated(aiAgentHarnessConfig);
        }
    }

    @Test
    public void test_updateById() {
        AiAgentHarnessConfig aiAgentHarnessConfig = buildTestConfig(uniqueClientId(), 1);
        aiAgentHarnessConfigDao.insert(aiAgentHarnessConfig);
        try {
            aiAgentHarnessConfig.setClientId(uniqueClientId());
            aiAgentHarnessConfig.setSequence(2);
            aiAgentHarnessConfig.setStepPrompt("DAO 更新测试");
            int result = aiAgentHarnessConfigDao.updateById(aiAgentHarnessConfig);
            Assert.assertEquals(1, result);
            log.info("更新结果: {}", result);
        } finally {
            deleteIfCreated(aiAgentHarnessConfig);
        }
    }

    @Test
    public void test_queryById() {
        AiAgentHarnessConfig aiAgentHarnessConfig = aiAgentHarnessConfigDao.queryById(1L);
        Assert.assertNotNull(aiAgentHarnessConfig);
        log.info("根据ID查询结果: {}", aiAgentHarnessConfig);
    }

    @Test
    public void test_queryByAgentId() {
        List<AiAgentHarnessConfig> aiAgentHarnessConfigs = aiAgentHarnessConfigDao.queryByAgentId("1");
        Assert.assertFalse(aiAgentHarnessConfigs.isEmpty());
        log.info("根据智能体ID查询结果数量: {}", aiAgentHarnessConfigs.size());
    }

    @Test
    public void test_queryByClientId() {
        List<AiAgentHarnessConfig> aiAgentHarnessConfigs = aiAgentHarnessConfigDao.queryByClientId("2103");
        Assert.assertFalse(aiAgentHarnessConfigs.isEmpty());
        log.info("根据客户端ID查询结果数量: {}", aiAgentHarnessConfigs.size());
    }

    @Test
    public void test_queryByAgentIdAndClientId() {
        AiAgentHarnessConfig aiAgentHarnessConfig = aiAgentHarnessConfigDao.queryByAgentIdAndClientId("1", "2101");
        Assert.assertNotNull(aiAgentHarnessConfig);
        log.info("根据智能体ID和客户端ID查询结果: {}", aiAgentHarnessConfig);
    }

    @Test
    public void test_queryAll() {
        List<AiAgentHarnessConfig> aiAgentHarnessConfigs = aiAgentHarnessConfigDao.queryAll();
        Assert.assertFalse(aiAgentHarnessConfigs.isEmpty());
        log.info("查询所有关联配置数量: {}", aiAgentHarnessConfigs.size());
    }

    @Test
    public void test_deleteById() {
        AiAgentHarnessConfig aiAgentHarnessConfig = buildTestConfig(uniqueClientId(), 1);
        aiAgentHarnessConfigDao.insert(aiAgentHarnessConfig);
        int result = aiAgentHarnessConfigDao.deleteById(aiAgentHarnessConfig.getId());
        Assert.assertEquals(1, result);
        log.info("根据ID删除结果: {}", result);
    }

    @Test
    public void test_deleteByAgentId() {
        AiAgentHarnessConfig aiAgentHarnessConfig = buildTestConfig(uniqueClientId(), 1);
        aiAgentHarnessConfigDao.insert(aiAgentHarnessConfig);
        int result = aiAgentHarnessConfigDao.deleteByAgentId(TEST_AGENT_ID);
        Assert.assertTrue(result >= 1);
        log.info("根据智能体ID删除结果: {}", result);
    }

    private AiAgentHarnessConfig buildTestConfig(String clientId, int sequence) {
        return AiAgentHarnessConfig.builder()
                .agentId(TEST_AGENT_ID)
                .clientId(clientId)
                .clientName("DAO测试客户端")
                .clientType("DEFAULT")
                .sequence(sequence)
                .stepPrompt("DAO 测试提示词")
                .createTime(LocalDateTime.now())
                .build();
    }

    private String uniqueClientId() {
        return "test-client-" + System.nanoTime();
    }

    private void deleteIfCreated(AiAgentHarnessConfig aiAgentHarnessConfig) {
        if (aiAgentHarnessConfig.getId() != null) {
            aiAgentHarnessConfigDao.deleteById(aiAgentHarnessConfig.getId());
        }
    }
}
