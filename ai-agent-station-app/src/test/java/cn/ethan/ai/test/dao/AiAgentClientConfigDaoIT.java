package cn.ethan.ai.test.dao;

import cn.ethan.ai.infrastructure.dao.IAiAgentClientConfigDao;
import cn.ethan.ai.infrastructure.dao.po.AiAgentClientConfig;
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
public class AiAgentClientConfigDaoIT {

    private static final String TEST_AGENT_ID = "test-flow-dao";

    @Resource
    private IAiAgentClientConfigDao aiAgentClientConfigDao;

    @Test
    public void test_insert() {
        AiAgentClientConfig aiAgentClientConfig = buildTestConfig(uniqueClientId(), 1);
        int result = aiAgentClientConfigDao.insert(aiAgentClientConfig);
        Assertions.assertEquals(1, result);
        Assertions.assertNotNull(aiAgentClientConfig.getId());
        log.info("插入结果: {}, 生成ID: {}", result, aiAgentClientConfig.getId());
    }

    @Test
    public void test_updateById() {
        AiAgentClientConfig aiAgentClientConfig = buildTestConfig(uniqueClientId(), 1);
        aiAgentClientConfigDao.insert(aiAgentClientConfig);
        aiAgentClientConfig.setClientId(uniqueClientId());
        aiAgentClientConfig.setSequence(2);
        aiAgentClientConfig.setStepPrompt("DAO 更新测试");
        int result = aiAgentClientConfigDao.updateById(aiAgentClientConfig);
        Assertions.assertEquals(1, result);
        log.info("更新结果: {}", result);
    }

    @Test
    public void test_queryById() {
        AiAgentClientConfig expected = insertTestConfig();
        AiAgentClientConfig actual = aiAgentClientConfigDao.queryById(expected.getId());
        Assertions.assertNotNull(actual);
        Assertions.assertEquals(expected.getClientId(), actual.getClientId());
        log.info("根据ID查询结果: {}", actual);
    }

    @Test
    public void test_queryByAgentId() {
        AiAgentClientConfig expected = insertTestConfig();
        List<AiAgentClientConfig> aiAgentClientConfigs = aiAgentClientConfigDao.queryByAgentId(TEST_AGENT_ID);
        Assertions.assertTrue(aiAgentClientConfigs.stream()
                .anyMatch(config -> expected.getId().equals(config.getId())));
        log.info("根据智能体ID查询结果数量: {}", aiAgentClientConfigs.size());
    }

    @Test
    public void test_queryByClientId() {
        AiAgentClientConfig expected = insertTestConfig();
        List<AiAgentClientConfig> aiAgentClientConfigs = aiAgentClientConfigDao.queryByClientId(expected.getClientId());
        Assertions.assertTrue(aiAgentClientConfigs.stream()
                .anyMatch(config -> expected.getId().equals(config.getId())));
        log.info("根据客户端ID查询结果数量: {}", aiAgentClientConfigs.size());
    }

    @Test
    public void test_queryByAgentIdAndClientId() {
        AiAgentClientConfig expected = insertTestConfig();
        AiAgentClientConfig actual = aiAgentClientConfigDao.queryByAgentIdAndClientId(
                TEST_AGENT_ID, expected.getClientId());
        Assertions.assertNotNull(actual);
        Assertions.assertEquals(expected.getId(), actual.getId());
        log.info("根据智能体ID和客户端ID查询结果: {}", actual);
    }

    @Test
    public void test_queryAll() {
        AiAgentClientConfig expected = insertTestConfig();
        List<AiAgentClientConfig> aiAgentClientConfigs = aiAgentClientConfigDao.queryAll();
        Assertions.assertTrue(aiAgentClientConfigs.stream()
                .anyMatch(config -> expected.getId().equals(config.getId())));
        log.info("查询所有关联配置数量: {}", aiAgentClientConfigs.size());
    }

    @Test
    public void test_deleteById() {
        AiAgentClientConfig aiAgentClientConfig = buildTestConfig(uniqueClientId(), 1);
        aiAgentClientConfigDao.insert(aiAgentClientConfig);
        int result = aiAgentClientConfigDao.deleteById(aiAgentClientConfig.getId());
        Assertions.assertEquals(1, result);
        log.info("根据ID删除结果: {}", result);
    }

    @Test
    public void test_deleteByAgentId() {
        AiAgentClientConfig aiAgentClientConfig = buildTestConfig(uniqueClientId(), 1);
        aiAgentClientConfigDao.insert(aiAgentClientConfig);
        int result = aiAgentClientConfigDao.deleteByAgentId(TEST_AGENT_ID);
        Assertions.assertTrue(result >= 1);
        log.info("根据智能体ID删除结果: {}", result);
    }

    private AiAgentClientConfig buildTestConfig(String clientId, int sequence) {
        return AiAgentClientConfig.builder()
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

    private AiAgentClientConfig insertTestConfig() {
        AiAgentClientConfig config = buildTestConfig(uniqueClientId(), 1);
        Assertions.assertEquals(1, aiAgentClientConfigDao.insert(config));
        Assertions.assertNotNull(config.getId());
        return config;
    }
}
