package cn.ethan.ai.test.infrastructure;

import cn.ethan.ai.domain.agent.adapter.port.IAfterSalesToolPort;
import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundResult;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunRecord;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRunRecord;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentRunStatus;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentStepRunStatus;
import cn.ethan.ai.domain.agent.service.AfterSalesGraphRuntime;
import cn.ethan.ai.infrastructure.adapter.repository.AfterSalesRepository;
import cn.ethan.ai.infrastructure.adapter.repository.AgentRunRepository;
import cn.ethan.ai.infrastructure.dao.AfterSalesCaseMapper;
import cn.ethan.ai.infrastructure.dao.AfterSalesOutboxMapper;
import cn.ethan.ai.infrastructure.dao.AgentRunMapper;
import cn.ethan.ai.infrastructure.dao.AgentStepRunMapper;
import cn.ethan.ai.infrastructure.dao.DemoOrderMapper;
import cn.ethan.ai.infrastructure.dao.RefundCommandMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.bsc.langgraph4j.checkpoint.CreateOption;
import org.bsc.langgraph4j.checkpoint.MysqlSaver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Testcontainers(disabledWithoutDocker = true)
public class MysqlAfterSalesPersistenceIT {

    private static final String[] EXPECTED_TABLES = {
            "agent_run",
            "agent_step_run",
            "demo_order",
            "after_sales_case",
            "refund_command",
            "after_sales_outbox",
            "LANGRAPH4J_THREAD",
            "LANGRAPH4J_CHECKPOINT"
    };

    private static final String[] MAPPER_FILES = {
            "agent_run_mapper.xml",
            "agent_step_run_mapper.xml",
            "demo_order_mapper.xml",
            "after_sales_case_mapper.xml",
            "refund_command_mapper.xml",
            "after_sales_outbox_mapper.xml"
    };

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.36")
            .withDatabaseName("ai_agent_station")
            .withUsername("test")
            .withPassword("test");

    @Test
    void shouldPersistAllEightTablesAndResumeRefundIdempotently() throws Exception {
        MysqlDataSource dataSource = dataSource();
        createSchema(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Assertions.assertEquals(EXPECTED_TABLES.length, countExpectedTables(jdbc));
        Assertions.assertEquals(EXPECTED_TABLES.length, countAllTables(jdbc));

        SqlSessionTemplate sqlSessionTemplate = sqlSessionTemplate(dataSource);
        AgentRunRepository runRepository = new AgentRunRepository(
                sqlSessionTemplate.getMapper(AgentRunMapper.class),
                sqlSessionTemplate.getMapper(AgentStepRunMapper.class)
        );
        AfterSalesRepository afterSalesRepository = new AfterSalesRepository(
                sqlSessionTemplate.getMapper(DemoOrderMapper.class),
                sqlSessionTemplate.getMapper(AfterSalesCaseMapper.class),
                sqlSessionTemplate.getMapper(RefundCommandMapper.class),
                sqlSessionTemplate.getMapper(AfterSalesOutboxMapper.class),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );

        LocalDateTime now = LocalDateTime.now();
        runRepository.createRun(AgentRunRecord.builder()
                .runId("run-1")
                .agentId("durable-after-sales")
                .sessionId("session-1")
                .userMessage("订单退款")
                .status(AgentRunStatus.RUNNING)
                .startTime(now)
                .build());
        runRepository.createStep(AgentStepRunRecord.builder()
                .runId("run-1")
                .stepId("step-1")
                .stepName("INTAKE")
                .stepOrder(1)
                .stepType("AFTER_SALES_GRAPH")
                .status(AgentStepRunStatus.SUCCESS)
                .costMillis(1L)
                .startTime(now)
                .endTime(now)
                .build());
        afterSalesRepository.createCase("run-1", "case-1", "demo-user-1", "session-1", "退款");

        AfterSalesOrderSnapshot order = afterSalesRepository.findOrder("ORDER-PAID-001").orElseThrow();
        Assertions.assertEquals("PAID", order.status());
        Assertions.assertEquals(AfterSalesStage.INTAKE.name(),
                afterSalesRepository.findCase("run-1").orElseThrow().stage());

        AfterSalesGraphRuntime firstProcess = graph(dataSource, afterSalesRepository);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(AfterSalesAgentState.RUN_ID, "run-1");
        input.put(AfterSalesAgentState.CASE_ID, "case-1");
        input.put(AfterSalesAgentState.USER_ID, "demo-user-1");
        input.put(AfterSalesAgentState.SESSION_ID, "session-1");
        input.put(AfterSalesAgentState.ORDER_ID, "ORDER-PAID-001");
        input.put(AfterSalesAgentState.ORDER_OWNER_ID, "demo-user-1");
        input.put(AfterSalesAgentState.ORDER_STATUS, "PAID");
        input.put(AfterSalesAgentState.REFUND_REASON, "DAMAGED");

        AfterSalesAgentState waiting = firstProcess.execute(input, "run-1");
        String checkpointId = firstProcess.currentSnapshot("run-1").orElseThrow()
                .config().checkPointId().orElseThrow();
        Assertions.assertEquals(AfterSalesStage.READY_FOR_APPROVAL, waiting.stage());

        AfterSalesGraphRuntime restartedProcess = graph(dataSource, afterSalesRepository);
        Assertions.assertTrue(restartedProcess.currentSnapshot("run-1").isPresent());
        AfterSalesAgentState completed = restartedProcess.resume(
                Map.of(AfterSalesAgentState.APPROVAL_DECISION, "APPROVE"),
                "run-1",
                checkpointId
        );

        Assertions.assertEquals(AfterSalesStage.COMPLETED, completed.stage());
        AfterSalesRefundResult replay = afterSalesRepository.executeRefund(
                "case-1", "ORDER-PAID-001", "demo-user-1", "case-1:REFUND");
        Assertions.assertTrue(replay.success());
        Assertions.assertTrue(replay.idempotentReplay());

        assertRowCount(jdbc, "agent_run", 1);
        assertRowCount(jdbc, "agent_step_run", 1);
        assertRowCount(jdbc, "demo_order", 3);
        assertRowCount(jdbc, "after_sales_case", 1);
        assertRowCount(jdbc, "refund_command", 1);
        assertRowCount(jdbc, "after_sales_outbox", 1);
        Assertions.assertTrue(rowCount(jdbc, "LANGRAPH4J_THREAD") >= 1);
        Assertions.assertTrue(rowCount(jdbc, "LANGRAPH4J_CHECKPOINT") >= 1);
        Assertions.assertEquals("REFUNDED", afterSalesRepository.findOrder("ORDER-PAID-001").orElseThrow().status());
    }

    private MysqlDataSource dataSource() {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }

    private AfterSalesGraphRuntime graph(MysqlDataSource dataSource,
                                         AfterSalesRepository repository) throws Exception {
        return new AfterSalesGraphRuntime(
                MysqlSaver.builder()
                        .dataSource(dataSource)
                        .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                        .build(),
                new UnusedToolPort(),
                repository
        );
    }

    private SqlSessionTemplate sqlSessionTemplate(MysqlDataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        Resource[] mapperResources = new Resource[MAPPER_FILES.length];
        Path mapperDirectory = resolveMapperDirectory();
        for (int index = 0; index < MAPPER_FILES.length; index++) {
            mapperResources[index] = new FileSystemResource(mapperDirectory.resolve(MAPPER_FILES[index]));
        }
        factoryBean.setMapperLocations(mapperResources);
        SqlSessionFactory sqlSessionFactory = factoryBean.getObject();
        if (sqlSessionFactory == null) {
            throw new IllegalStateException("MyBatis SqlSessionFactory creation failed");
        }
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    private void createSchema(MysqlDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(resolveProjectPath(
                            "docs", "dev-ops", "mysql", "sql", "ai-agent-station.sql"
                    )));
        }
    }

    private Path resolveMapperDirectory() {
        return resolveProjectPath("ai-agent-station-app", "src", "main", "resources", "mybatis", "mapper");
    }

    private Path resolveProjectPath(String... segments) {
        Path root = Path.of("").toAbsolutePath();
        Path other = Path.of("", segments);
        Path direct = root.resolve(other);
        if (Files.exists(direct)) {
            return direct;
        }
        Path fromModule = root.resolve("..").normalize().resolve(other);
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        throw new IllegalStateException("Cannot resolve project path " + String.join("/", segments));
    }

    private int countExpectedTables(JdbcTemplate jdbc) {
        String placeholders = String.join(",", java.util.Arrays.stream(EXPECTED_TABLES).map(ignored -> "?").toList());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ("
                        + placeholders + ")",
                Integer.class,
                (Object[]) EXPECTED_TABLES
        );
        return count == null ? 0 : count;
    }

    private int countAllTables(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()",
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private void assertRowCount(JdbcTemplate jdbc, String tableName, int expected) {
        Assertions.assertEquals(expected, rowCount(jdbc, tableName), tableName);
    }

    private int rowCount(JdbcTemplate jdbc, String tableName) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
        return count == null ? 0 : count;
    }

    private static final class UnusedToolPort implements IAfterSalesToolPort {
        @Override
        public AfterSalesToolRequest proposeOrderQuery(String userMessage, String userId, String orderIdHint,
                                                       String refundReason, String correction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AfterSalesToolResult executeOrderQuery(AfterSalesToolRequest request,
                                                      String userId,
                                                      String userMessage) {
            throw new UnsupportedOperationException();
        }
    }
}
