package cn.ethan.ai.test.dao;

import cn.ethan.ai.infrastructure.dao.IAiAgentFlowConfigDao;
import cn.ethan.ai.infrastructure.dao.po.AiAgentFlowConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class AiAgentFlowConfigDaoTest {

    private static final String TEST_AGENT_ID = "test-flow-dao";

    @Resource
    private IAiAgentFlowConfigDao aiAgentFlowConfigDao;

    @Test
    public void test_insert() {
        AiAgentFlowConfig aiAgentFlowConfig = buildTestConfig(uniqueClientId(), 1);
        try {
            int result = aiAgentFlowConfigDao.insert(aiAgentFlowConfig);
            Assert.assertEquals(1, result);
            Assert.assertNotNull(aiAgentFlowConfig.getId());
            log.info("插入结果: {}, 生成ID: {}", result, aiAgentFlowConfig.getId());
        } finally {
            deleteIfCreated(aiAgentFlowConfig);
        }
    }

    @Test
    public void test_updateById() {
        AiAgentFlowConfig aiAgentFlowConfig = buildTestConfig(uniqueClientId(), 1);
        aiAgentFlowConfigDao.insert(aiAgentFlowConfig);
        try {
            aiAgentFlowConfig.setClientId(uniqueClientId());
            aiAgentFlowConfig.setSequence(2);
            aiAgentFlowConfig.setStepPrompt("DAO 更新测试");
            int result = aiAgentFlowConfigDao.updateById(aiAgentFlowConfig);
            Assert.assertEquals(1, result);
            log.info("更新结果: {}", result);
        } finally {
            deleteIfCreated(aiAgentFlowConfig);
        }
    }

    @Test
    public void test_queryById() {
        AiAgentFlowConfig aiAgentFlowConfig = aiAgentFlowConfigDao.queryById(1L);
        Assert.assertNotNull(aiAgentFlowConfig);
        log.info("根据ID查询结果: {}", aiAgentFlowConfig);
    }

    @Test
    public void test_queryByAgentId() {
        List<AiAgentFlowConfig> aiAgentFlowConfigs = aiAgentFlowConfigDao.queryByAgentId("1");
        Assert.assertFalse(aiAgentFlowConfigs.isEmpty());
        log.info("根据智能体ID查询结果数量: {}", aiAgentFlowConfigs.size());
    }

    @Test
    public void test_queryByClientId() {
        List<AiAgentFlowConfig> aiAgentFlowConfigs = aiAgentFlowConfigDao.queryByClientId("2103");
        Assert.assertFalse(aiAgentFlowConfigs.isEmpty());
        log.info("根据客户端ID查询结果数量: {}", aiAgentFlowConfigs.size());
    }

    @Test
    public void test_queryByAgentIdAndClientId() {
        AiAgentFlowConfig aiAgentFlowConfig = aiAgentFlowConfigDao.queryByAgentIdAndClientId("1", "2101");
        Assert.assertNotNull(aiAgentFlowConfig);
        log.info("根据智能体ID和客户端ID查询结果: {}", aiAgentFlowConfig);
    }

    @Test
    public void test_queryAll() {
        List<AiAgentFlowConfig> aiAgentFlowConfigs = aiAgentFlowConfigDao.queryAll();
        Assert.assertFalse(aiAgentFlowConfigs.isEmpty());
        log.info("查询所有关联配置数量: {}", aiAgentFlowConfigs.size());
    }

    @Test
    public void test_deleteById() {
        AiAgentFlowConfig aiAgentFlowConfig = buildTestConfig(uniqueClientId(), 1);
        aiAgentFlowConfigDao.insert(aiAgentFlowConfig);
        int result = aiAgentFlowConfigDao.deleteById(aiAgentFlowConfig.getId());
        Assert.assertEquals(1, result);
        log.info("根据ID删除结果: {}", result);
    }

    @Test
    public void test_deleteByAgentId() {
        AiAgentFlowConfig aiAgentFlowConfig = buildTestConfig(uniqueClientId(), 1);
        aiAgentFlowConfigDao.insert(aiAgentFlowConfig);
        int result = aiAgentFlowConfigDao.deleteByAgentId(TEST_AGENT_ID);
        Assert.assertTrue(result >= 1);
        log.info("根据智能体ID删除结果: {}", result);
    }

    private AiAgentFlowConfig buildTestConfig(String clientId, int sequence) {
        return AiAgentFlowConfig.builder()
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

    private void deleteIfCreated(AiAgentFlowConfig aiAgentFlowConfig) {
        if (aiAgentFlowConfig.getId() != null) {
            aiAgentFlowConfigDao.deleteById(aiAgentFlowConfig.getId());
        }
    }

}
