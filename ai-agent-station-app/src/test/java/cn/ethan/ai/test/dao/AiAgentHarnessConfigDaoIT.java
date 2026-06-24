package cn.ethan.ai.test.dao;

import cn.ethan.ai.infrastructure.dao.IAiAgentHarnessConfigDao;
import cn.ethan.ai.infrastructure.dao.po.AiAgentHarnessConfig;
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
public class AiAgentHarnessConfigDaoIT {

    private static final String TEST_AGENT_ID = "test-flow-dao";

    @Resource
    private IAiAgentHarnessConfigDao aiAgentHarnessConfigDao;

    @Test
    public void test_insert() {
        AiAgentHarnessConfig aiAgentHarnessConfig = buildTestConfig(uniqueClientId(), 1);
        int result = aiAgentHarnessConfigDao.insert(aiAgentHarnessConfig);
        Assertions.assertEquals(1, result);
        Assertions.assertNotNull(aiAgentHarnessConfig.getId());
        log.info("插入结果: {}, 生成ID: {}", result, aiAgentHarnessConfig.getId());
    }

    @Test
    public void test_updateById() {
        AiAgentHarnessConfig aiAgentHarnessConfig = buildTestConfig(uniqueClientId(), 1);
        aiAgentHarnessConfigDao.insert(aiAgentHarnessConfig);
        aiAgentHarnessConfig.setClientId(uniqueClientId());
        aiAgentHarnessConfig.setSequence(2);
        aiAgentHarnessConfig.setStepPrompt("DAO 更新测试");
        int result = aiAgentHarnessConfigDao.updateById(aiAgentHarnessConfig);
        Assertions.assertEquals(1, result);
        log.info("更新结果: {}", result);
    }

    @Test
    public void test_queryById() {
        AiAgentHarnessConfig expected = insertTestConfig();
        AiAgentHarnessConfig actual = aiAgentHarnessConfigDao.queryById(expected.getId());
        Assertions.assertNotNull(actual);
        Assertions.assertEquals(expected.getClientId(), actual.getClientId());
        log.info("根据ID查询结果: {}", actual);
    }

    @Test
    public void test_queryByAgentId() {
        AiAgentHarnessConfig expected = insertTestConfig();
        List<AiAgentHarnessConfig> aiAgentHarnessConfigs = aiAgentHarnessConfigDao.queryByAgentId(TEST_AGENT_ID);
        Assertions.assertTrue(aiAgentHarnessConfigs.stream()
                .anyMatch(config -> expected.getId().equals(config.getId())));
        log.info("根据智能体ID查询结果数量: {}", aiAgentHarnessConfigs.size());
    }

    @Test
    public void test_queryByClientId() {
        AiAgentHarnessConfig expected = insertTestConfig();
        List<AiAgentHarnessConfig> aiAgentHarnessConfigs = aiAgentHarnessConfigDao.queryByClientId(expected.getClientId());
        Assertions.assertTrue(aiAgentHarnessConfigs.stream()
                .anyMatch(config -> expected.getId().equals(config.getId())));
        log.info("根据客户端ID查询结果数量: {}", aiAgentHarnessConfigs.size());
    }

    @Test
    public void test_queryByAgentIdAndClientId() {
        AiAgentHarnessConfig expected = insertTestConfig();
        AiAgentHarnessConfig actual = aiAgentHarnessConfigDao.queryByAgentIdAndClientId(
                TEST_AGENT_ID, expected.getClientId());
        Assertions.assertNotNull(actual);
        Assertions.assertEquals(expected.getId(), actual.getId());
        log.info("根据智能体ID和客户端ID查询结果: {}", actual);
    }

    @Test
    public void test_queryAll() {
        AiAgentHarnessConfig expected = insertTestConfig();
        List<AiAgentHarnessConfig> aiAgentHarnessConfigs = aiAgentHarnessConfigDao.queryAll();
        Assertions.assertTrue(aiAgentHarnessConfigs.stream()
                .anyMatch(config -> expected.getId().equals(config.getId())));
        log.info("查询所有关联配置数量: {}", aiAgentHarnessConfigs.size());
    }

    @Test
    public void test_deleteById() {
        AiAgentHarnessConfig aiAgentHarnessConfig = buildTestConfig(uniqueClientId(), 1);
        aiAgentHarnessConfigDao.insert(aiAgentHarnessConfig);
        int result = aiAgentHarnessConfigDao.deleteById(aiAgentHarnessConfig.getId());
        Assertions.assertEquals(1, result);
        log.info("根据ID删除结果: {}", result);
    }

    @Test
    public void test_deleteByAgentId() {
        AiAgentHarnessConfig aiAgentHarnessConfig = buildTestConfig(uniqueClientId(), 1);
        aiAgentHarnessConfigDao.insert(aiAgentHarnessConfig);
        int result = aiAgentHarnessConfigDao.deleteByAgentId(TEST_AGENT_ID);
        Assertions.assertTrue(result >= 1);
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

    private AiAgentHarnessConfig insertTestConfig() {
        AiAgentHarnessConfig config = buildTestConfig(uniqueClientId(), 1);
        Assertions.assertEquals(1, aiAgentHarnessConfigDao.insert(config));
        Assertions.assertNotNull(config.getId());
        return config;
    }
}
