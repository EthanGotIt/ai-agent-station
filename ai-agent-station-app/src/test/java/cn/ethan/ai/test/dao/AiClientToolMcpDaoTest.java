package cn.ethan.ai.test.dao;

import cn.ethan.ai.test.support.ManualTestGate;
import cn.ethan.ai.infrastructure.dao.IAiClientToolMcpDao;
import cn.ethan.ai.infrastructure.dao.po.AiClientToolMcp;
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
public class AiClientToolMcpDaoTest {

    private static final String TEST_MCP_ID_PREFIX = "test-mcp-";

    @Resource
    private IAiClientToolMcpDao aiClientToolMcpDao;

    @Test
    public void test_insert() {
        ManualTestGate.requireDbMutation("AiClientToolMcpDaoTest.test_insert");

        AiClientToolMcp aiClientToolMcp = buildTestMcp(uniqueMcpId());
        try {
            int result = aiClientToolMcpDao.insert(aiClientToolMcp);
            Assert.assertEquals(1, result);
            Assert.assertNotNull(aiClientToolMcp.getId());
            log.info("插入结果: {}, 生成ID: {}", result, aiClientToolMcp.getId());
        } finally {
            deleteIfCreated(aiClientToolMcp);
        }
    }

    @Test
    public void test_updateById() {
        AiClientToolMcp aiClientToolMcp = buildTestMcp(uniqueMcpId());
        aiClientToolMcpDao.insert(aiClientToolMcp);
        try {
            aiClientToolMcp.setMcpName("DAO测试 MCP 更新");
            aiClientToolMcp.setTransportType("stdio");
            aiClientToolMcp.setTransportConfig("{\"command\":\"npx.cmd\",\"args\":[\"-y\",\"test-mcp\"]}");
            aiClientToolMcp.setRequestTimeout(300);
            aiClientToolMcp.setUpdateTime(LocalDateTime.now());
            int result = aiClientToolMcpDao.updateById(aiClientToolMcp);
            Assert.assertEquals(1, result);
            log.info("更新结果: {}", result);
        } finally {
            deleteIfCreated(aiClientToolMcp);
        }
    }

    @Test
    public void test_updateByMcpId() {
        AiClientToolMcp aiClientToolMcp = buildTestMcp(uniqueMcpId());
        aiClientToolMcpDao.insert(aiClientToolMcp);
        try {
            aiClientToolMcp.setMcpName("DAO测试 MCP 按业务ID更新");
            aiClientToolMcp.setRequestTimeout(240);
            aiClientToolMcp.setUpdateTime(LocalDateTime.now());
            int result = aiClientToolMcpDao.updateByMcpId(aiClientToolMcp);
            Assert.assertEquals(1, result);
            log.info("根据MCP ID更新结果: {}", result);
        } finally {
            deleteIfCreated(aiClientToolMcp);
        }
    }

    @Test
    public void test_deleteById() {
        AiClientToolMcp aiClientToolMcp = buildTestMcp(uniqueMcpId());
        aiClientToolMcpDao.insert(aiClientToolMcp);
        int result = aiClientToolMcpDao.deleteById(aiClientToolMcp.getId());
        Assert.assertEquals(1, result);
        log.info("删除结果: {}", result);
    }

    @Test
    public void test_deleteByMcpId() {
        AiClientToolMcp aiClientToolMcp = buildTestMcp(uniqueMcpId());
        aiClientToolMcpDao.insert(aiClientToolMcp);
        int result = aiClientToolMcpDao.deleteByMcpId(aiClientToolMcp.getMcpId());
        Assert.assertEquals(1, result);
        log.info("根据MCP ID删除结果: {}", result);
    }

    @Test
    public void test_queryById() {
        AiClientToolMcp aiClientToolMcp = aiClientToolMcpDao.queryById(1L);
        Assert.assertNotNull(aiClientToolMcp);
        log.info("根据ID查询结果: {}", aiClientToolMcp);
    }

    @Test
    public void test_queryByMcpId() {
        AiClientToolMcp aiClientToolMcp = aiClientToolMcpDao.queryByMcpId("5001");
        Assert.assertNotNull(aiClientToolMcp);
        log.info("根据MCP ID查询结果: {}", aiClientToolMcp);
    }

    @Test
    public void test_queryAll() {
        List<AiClientToolMcp> aiClientToolMcpList = aiClientToolMcpDao.queryAll();
        Assert.assertFalse(aiClientToolMcpList.isEmpty());
        log.info("查询所有MCP工具配置数量: {}", aiClientToolMcpList.size());
    }

    @Test
    public void test_queryByStatus() {
        List<AiClientToolMcp> aiClientToolMcpList = aiClientToolMcpDao.queryByStatus(1);
        Assert.assertFalse(aiClientToolMcpList.isEmpty());
        log.info("根据状态查询结果数量: {}", aiClientToolMcpList.size());
    }

    @Test
    public void test_queryByTransportType() {
        List<AiClientToolMcp> aiClientToolMcpList = aiClientToolMcpDao.queryByTransportType("stdio");
        Assert.assertFalse(aiClientToolMcpList.isEmpty());
        log.info("根据传输类型查询结果数量: {}", aiClientToolMcpList.size());
    }

    @Test
    public void test_queryEnabledMcps() {
        List<AiClientToolMcp> aiClientToolMcpList = aiClientToolMcpDao.queryEnabledMcps();
        Assert.assertFalse(aiClientToolMcpList.isEmpty());
        log.info("查询启用的MCP工具配置数量: {}", aiClientToolMcpList.size());
    }

    private AiClientToolMcp buildTestMcp(String mcpId) {
        return AiClientToolMcp.builder()
                .mcpId(mcpId)
                .mcpName("DAO测试 MCP")
                .transportType("streamable_http")
                .transportConfig("{\"baseUri\":\"http://localhost:8080\",\"endpoint\":\"/mcp\"}")
                .requestTimeout(180)
                .status(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
    }

    private String uniqueMcpId() {
        return TEST_MCP_ID_PREFIX + System.nanoTime();
    }

    private void deleteIfCreated(AiClientToolMcp aiClientToolMcp) {
        if (aiClientToolMcp.getId() != null) {
            aiClientToolMcpDao.deleteById(aiClientToolMcp.getId());
        }
    }

}



