package cn.ethan.ai.test.dao;

import cn.ethan.ai.test.support.ManualTestGate;
import cn.ethan.ai.infrastructure.dao.IAiClientApiDao;
import cn.ethan.ai.infrastructure.dao.po.AiClientApi;
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
public class AiClientApiDaoIT {

    @Resource
    private IAiClientApiDao aiClientApiDao;

    @Test
    public void test_insert() {
        ManualTestGate.requireDbMutation("AiClientApiDaoTest.test_insert");

        AiClientApi aiClientApi = AiClientApi.builder()
                .apiId("test_api_001")
                .baseUrl("https://api.openai.com")
                .apiKey("test-api-key")
                .status(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

        int result = aiClientApiDao.insert(aiClientApi);
        log.info("插入结果: {}, 生成ID: {}", result, aiClientApi.getId());
    }

    @Test
    public void test_updateById() {
        AiClientApi aiClientApi = AiClientApi.builder()
                .id(1L)
                .apiId("test_api_001")
                .baseUrl("https://api.openai.com")
                .apiKey("updated-test-api-key")
                .status(1)
                .updateTime(LocalDateTime.now())
                .build();

        int result = aiClientApiDao.updateById(aiClientApi);
        log.info("更新结果: {}", result);
    }

    @Test
    public void test_updateByApiId() {
        AiClientApi aiClientApi = AiClientApi.builder()
                .apiId("test_api_001")
                .baseUrl("https://api.openai.com")
                .apiKey("updated-by-api-id")
                .status(1)
                .updateTime(LocalDateTime.now())
                .build();

        int result = aiClientApiDao.updateByApiId(aiClientApi);
        log.info("根据API ID更新结果: {}", result);
    }

    @Test
    public void test_deleteById() {
        int result = aiClientApiDao.deleteById(1L);
        log.info("删除结果: {}", result);
    }

    @Test
    public void test_deleteByApiId() {
        int result = aiClientApiDao.deleteByApiId("test_api_001");
        log.info("根据API ID删除结果: {}", result);
    }

    @Test
    public void test_queryById() {
        AiClientApi aiClientApi = aiClientApiDao.queryById(1L);
        log.info("查询结果: {}", aiClientApi);
    }

    @Test
    public void test_queryByApiId() {
        AiClientApi aiClientApi = aiClientApiDao.queryByApiId("openai-gpt-4o");
        log.info("根据API ID查询结果: {}", aiClientApi);
    }

    @Test
    public void test_queryEnabledApis() {
        List<AiClientApi> aiClientApis = aiClientApiDao.queryEnabledApis();
        log.info("查询启用的API配置: {}", aiClientApis);
    }

    @Test
    public void test_queryAll() {
        List<AiClientApi> aiClientApis = aiClientApiDao.queryAll();
        log.info("查询所有API配置: {}", aiClientApis);
    }

}



