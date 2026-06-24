package cn.ethan.ai.test.dao;

import cn.ethan.ai.test.support.ManualTestGate;
import cn.ethan.ai.infrastructure.dao.IAiClientDao;
import cn.ethan.ai.infrastructure.dao.po.AiClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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
public class AiClientDaoIT {

    @Resource
    private IAiClientDao aiClientDao;

    @Test
    public void test_insert() {
        ManualTestGate.requireDbMutation("AiClientDaoTest.test_insert");

        AiClient aiClient = AiClient.builder()
                .clientId("test_3006")
                .clientName("测试客户端")
                .description("这是一个测试客户端")
                .status(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

        int result = aiClientDao.insert(aiClient);
        log.info("插入结果: {}, 生成ID: {}", result, aiClient.getId());
    }

    @Test
    public void test_updateById() {
        AiClient aiClient = AiClient.builder()
                .id(1L)
                .clientId("test_3006")
                .clientName("更新后的测试客户端")
                .description("这是一个更新后的测试客户端")
                .status(1)
                .updateTime(LocalDateTime.now())
                .build();

        int result = aiClientDao.updateById(aiClient);
        log.info("更新结果: {}", result);
    }

    @Test
    public void test_updateByClientId() {
        AiClient aiClient = AiClient.builder()
                .clientId("test_3006")
                .clientName("通过ClientId更新的客户端")
                .description("通过ClientId更新的描述")
                .status(0)
                .updateTime(LocalDateTime.now())
                .build();

        int result = aiClientDao.updateByClientId(aiClient);
        log.info("通过ClientId更新结果: {}", result);
    }

    @Test
    public void test_queryById() {
        AiClient aiClient = aiClientDao.queryById(1L);
        log.info("根据ID查询结果: {}", aiClient);
    }

    @Test
    public void test_queryByClientId() {
        AiClient aiClient = aiClientDao.queryByClientId("3001");
        log.info("根据ClientId查询结果: {}", aiClient);
    }

    @Test
    public void test_queryEnabledClients() {
        List<AiClient> aiClients = aiClientDao.queryEnabledClients();
        log.info("查询启用的客户端数量: {}", aiClients.size());
        aiClients.forEach(client -> log.info("启用的客户端: {}", client));
    }

    @Test
    public void test_queryByClientName() {
        List<AiClient> aiClients = aiClientDao.queryByClientName("提示词");
        log.info("根据客户端名称查询结果数量: {}", aiClients.size());
        aiClients.forEach(client -> log.info("客户端: {}", client));
    }

    @Test
    public void test_queryAll() {
        List<AiClient> aiClients = aiClientDao.queryAll();
        log.info("查询所有客户端数量: {}", aiClients.size());
        aiClients.forEach(client -> log.info("客户端: {}", client));
    }

    @Test
    public void test_deleteById() {
        int result = aiClientDao.deleteById(1L);
        log.info("根据ID删除结果: {}", result);
    }

    @Test
    public void test_deleteByClientId() {
        int result = aiClientDao.deleteByClientId("test_3006");
        log.info("根据ClientId删除结果: {}", result);
    }

}



