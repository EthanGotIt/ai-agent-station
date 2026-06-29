package cn.ethan.ai.test.dao;

import cn.ethan.ai.test.support.ManualTestGate;
import cn.ethan.ai.infrastructure.dao.IAiClientModelDao;
import cn.ethan.ai.infrastructure.dao.po.AiClientModel;
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
public class AiClientModelDaoIT {

    private static final String TEST_MODEL_ID_PREFIX = "test-model-";

    @Resource
    private IAiClientModelDao aiClientModelDao;

    @Test
    public void test_insert() {
        ManualTestGate.requireDbMutation("AiClientModelDaoTest.test_insert");

        AiClientModel aiClientModel = buildTestModel(uniqueModelId());
        try {
            int result = aiClientModelDao.insert(aiClientModel);
            Assertions.assertEquals(1, result);
            Assertions.assertNotNull(aiClientModel.getId());
            log.info("插入结果: {}, 生成ID: {}", result, aiClientModel.getId());
        } finally {
            deleteIfCreated(aiClientModel);
        }
    }

    @Test
    public void test_updateById() {
        AiClientModel aiClientModel = buildTestModel(uniqueModelId());
        aiClientModelDao.insert(aiClientModel);
        try {
            aiClientModel.setModelName("DAO测试模型更新");
            aiClientModel.setUpdateTime(LocalDateTime.now());
            int result = aiClientModelDao.updateById(aiClientModel);
            Assertions.assertEquals(1, result);
            log.info("更新结果: {}", result);
        } finally {
            deleteIfCreated(aiClientModel);
        }
    }

    @Test
    public void test_updateByModelId() {
        AiClientModel aiClientModel = buildTestModel(uniqueModelId());
        aiClientModelDao.insert(aiClientModel);
        try {
            aiClientModel.setModelName("DAO测试模型按业务ID更新");
            aiClientModel.setUpdateTime(LocalDateTime.now());
            int result = aiClientModelDao.updateByModelId(aiClientModel);
            Assertions.assertEquals(1, result);
            log.info("根据模型ID更新结果: {}", result);
        } finally {
            deleteIfCreated(aiClientModel);
        }
    }

    @Test
    public void test_deleteById() {
        AiClientModel aiClientModel = buildTestModel(uniqueModelId());
        aiClientModelDao.insert(aiClientModel);
        int result = aiClientModelDao.deleteById(aiClientModel.getId());
        Assertions.assertEquals(1, result);
        log.info("删除结果: {}", result);
    }

    @Test
    public void test_deleteByModelId() {
        AiClientModel aiClientModel = buildTestModel(uniqueModelId());
        aiClientModelDao.insert(aiClientModel);
        int result = aiClientModelDao.deleteByModelId(aiClientModel.getModelId());
        Assertions.assertEquals(1, result);
        log.info("根据模型ID删除结果: {}", result);
    }

    @Test
    public void test_queryById() {
        AiClientModel aiClientModel = aiClientModelDao.queryById(1L);
        Assertions.assertNotNull(aiClientModel);
        log.info("根据ID查询结果: {}", aiClientModel);
    }

    @Test
    public void test_queryByModelId() {
        AiClientModel aiClientModel = aiClientModelDao.queryByModelId("model-qwen37-max");
        Assertions.assertNotNull(aiClientModel);
        log.info("根据模型ID查询结果: {}", aiClientModel);
    }

    @Test
    public void test_queryByApiId() {
        List<AiClientModel> aiClientModels = aiClientModelDao.queryByApiId("api-dashscope-openai");
        Assertions.assertFalse(aiClientModels.isEmpty());
        log.info("根据API配置ID查询结果数量: {}", aiClientModels.size());
        aiClientModels.forEach(model -> log.info("模型配置: {}", model));
    }

    @Test
    public void test_queryByModelType() {
        List<AiClientModel> aiClientModels = aiClientModelDao.queryByModelType("openai");
        Assertions.assertFalse(aiClientModels.isEmpty());
        log.info("根据模型类型查询结果数量: {}", aiClientModels.size());
        aiClientModels.forEach(model -> log.info("模型配置: {}", model));
    }

    @Test
    public void test_queryEnabledModels() {
        List<AiClientModel> aiClientModels = aiClientModelDao.queryEnabledModels();
        Assertions.assertFalse(aiClientModels.isEmpty());
        log.info("查询启用的模型配置数量: {}", aiClientModels.size());
        aiClientModels.forEach(model -> log.info("启用的模型配置: {}", model));
    }

    @Test
    public void test_queryAll() {
        List<AiClientModel> aiClientModels = aiClientModelDao.queryAll();
        Assertions.assertFalse(aiClientModels.isEmpty());
        log.info("查询所有模型配置数量: {}", aiClientModels.size());
        aiClientModels.forEach(model -> log.info("模型配置: {}", model));
    }

    private AiClientModel buildTestModel(String modelId) {
        return AiClientModel.builder()
                .modelId(modelId)
                .apiId("api-dashscope-openai")
                .modelName("DAO测试模型")
                .modelType("openai")
                .status(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
    }

    private String uniqueModelId() {
        return TEST_MODEL_ID_PREFIX + System.nanoTime();
    }

    private void deleteIfCreated(AiClientModel aiClientModel) {
        if (aiClientModel.getId() != null) {
            aiClientModelDao.deleteById(aiClientModel.getId());
        }
    }

}



