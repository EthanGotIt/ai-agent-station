package cn.ethan.ai.test.dao;

import cn.ethan.ai.test.support.ManualTestGate;
import cn.ethan.ai.infrastructure.dao.IAiClientRagOrderDao;
import cn.ethan.ai.infrastructure.dao.po.AiClientRagOrder;
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
public class AiClientRagOrderDaoIT {

    @Resource
    private IAiClientRagOrderDao aiClientRagOrderDao;

    @Test
    public void test_insert() {
        ManualTestGate.requireDbMutation("AiClientRagOrderDaoTest.test_insert");

        AiClientRagOrder aiClientRagOrder = AiClientRagOrder.builder()
                .ragId("test-rag-dao")
                .ragName("测试知识库")
                .knowledgeTag("测试标签")
                .status(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

        int result = aiClientRagOrderDao.insert(aiClientRagOrder);
        log.info("插入结果: {}, 生成ID: {}", result, aiClientRagOrder.getId());
    }

    @Test
    public void test_updateById() {
        AiClientRagOrder aiClientRagOrder = AiClientRagOrder.builder()
                .id(1L)
                .ragId("test-rag-dao")
                .ragName("更新后的测试知识库")
                .knowledgeTag("更新后的测试标签")
                .status(1)
                .updateTime(LocalDateTime.now())
                .build();

        int result = aiClientRagOrderDao.updateById(aiClientRagOrder);
        log.info("更新结果: {}", result);
    }

    @Test
    public void test_updateByRagId() {
        AiClientRagOrder aiClientRagOrder = AiClientRagOrder.builder()
                .ragId("test-rag-dao")
                .ragName("根据知识库ID更新的测试知识库")
                .knowledgeTag("根据知识库ID更新的测试标签")
                .status(1)
                .updateTime(LocalDateTime.now())
                .build();

        int result = aiClientRagOrderDao.updateByRagId(aiClientRagOrder);
        log.info("根据知识库ID更新结果: {}", result);
    }

    @Test
    public void test_deleteById() {
        int result = aiClientRagOrderDao.deleteById(1L);
        log.info("删除结果: {}", result);
    }

    @Test
    public void test_deleteByRagId() {
        int result = aiClientRagOrderDao.deleteByRagId("test-rag-dao");
        log.info("根据知识库ID删除结果: {}", result);
    }

    @Test
    public void test_queryById() {
        AiClientRagOrder aiClientRagOrder = aiClientRagOrderDao.queryById(3L);
        log.info("根据ID查询结果: {}", aiClientRagOrder);
    }

    @Test
    public void test_queryByRagId() {
        AiClientRagOrder aiClientRagOrder = aiClientRagOrderDao.queryByRagId("rag-agent-station");
        log.info("根据知识库ID查询结果: {}", aiClientRagOrder);
    }

    @Test
    public void test_queryEnabledRagOrders() {
        List<AiClientRagOrder> ragOrders = aiClientRagOrderDao.queryEnabledRagOrders();
        log.info("查询启用的知识库配置结果: {}", ragOrders);
    }

    @Test
    public void test_queryByKnowledgeTag() {
        List<AiClientRagOrder> ragOrders = aiClientRagOrderDao.queryByKnowledgeTag("生成文章提示词");
        log.info("根据知识标签查询结果: {}", ragOrders);
    }

    @Test
    public void test_queryAll() {
        List<AiClientRagOrder> ragOrders = aiClientRagOrderDao.queryAll();
        log.info("查询所有知识库配置结果: {}", ragOrders);
    }

}


