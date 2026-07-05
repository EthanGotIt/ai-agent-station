package cn.ethan.ai.test.infrastructure;

import cn.ethan.ai.domain.agent.model.AfterSalesDomainEvent;
import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundResult;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunRecord;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRecord;
import cn.ethan.ai.domain.agent.model.valobj.AgentTurnRecord;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentRunStatus;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentStepStatus;
import cn.ethan.ai.domain.agent.policy.RefundInformationGatheringPolicy;
import cn.ethan.ai.domain.agent.port.driving.IAfterSalesEventHandler;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
import cn.ethan.ai.infrastructure.adapter.commerce.LocalOrderGateway;
import cn.ethan.ai.infrastructure.adapter.commerce.LocalRefundGateway;
import cn.ethan.ai.infrastructure.adapter.event.AfterSalesOutboxDispatcher;
import cn.ethan.ai.infrastructure.adapter.event.IdempotentLocalEventPublisher;
import cn.ethan.ai.infrastructure.adapter.repository.AfterSalesRepository;
import cn.ethan.ai.infrastructure.adapter.repository.AgentRunRepository;
import cn.ethan.ai.infrastructure.adapter.statemachine.SpringStateMachineAdapter;
import cn.ethan.ai.infrastructure.dao.AfterSalesCaseMapper;
import cn.ethan.ai.infrastructure.dao.AfterSalesOutboxMapper;
import cn.ethan.ai.infrastructure.dao.AgentRunMapper;
import cn.ethan.ai.infrastructure.dao.AgentStepMapper;
import cn.ethan.ai.infrastructure.dao.AgentTurnMapper;
import cn.ethan.ai.infrastructure.dao.DemoOrderMapper;
import cn.ethan.ai.infrastructure.dao.RefundCommandMapper;
import cn.ethan.ai.infrastructure.dao.po.AfterSalesOutboxPO;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Testcontainers(disabledWithoutDocker = true)
public class MysqlAfterSalesPersistenceIT {

    private static final String[] EXPECTED_TABLES = {
            "agent_run",
            "agent_step",
            "agent_turn",
            "demo_order",
            "after_sales_case",
            "refund_command",
            "after_sales_outbox",
            "after_sales_event_consume"
    };

    private static final String[] MAPPER_FILES = {
            "agent_run_mapper.xml",
            "agent_step_mapper.xml",
            "agent_turn_mapper.xml",
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
    void shouldPersistAllTenTablesAndCompleteOutboxRetryAndIdempotentConsumption() throws Exception {
        MysqlDataSource dataSource = dataSource();
        createSchema(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Assertions.assertEquals(EXPECTED_TABLES.length, countExpectedTables(jdbc));
        Assertions.assertEquals(EXPECTED_TABLES.length, countAllTables(jdbc));

        SqlSessionTemplate sqlSessionTemplate = sqlSessionTemplate(dataSource);
        AgentRunRepository runRepository = new AgentRunRepository(
                sqlSessionTemplate.getMapper(AgentRunMapper.class),
                sqlSessionTemplate.getMapper(AgentStepMapper.class),
                sqlSessionTemplate.getMapper(AgentTurnMapper.class)
        );
        DemoOrderMapper demoOrderMapper = sqlSessionTemplate.getMapper(DemoOrderMapper.class);
        LocalOrderGateway orderGateway = new LocalOrderGateway(demoOrderMapper);
        AfterSalesRepository afterSalesRepository = new AfterSalesRepository(
                orderGateway,
                new LocalRefundGateway(demoOrderMapper, orderGateway),
                sqlSessionTemplate.getMapper(AfterSalesCaseMapper.class),
                sqlSessionTemplate.getMapper(RefundCommandMapper.class),
                sqlSessionTemplate.getMapper(AfterSalesOutboxMapper.class),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );

        LocalDateTime now = LocalDateTime.now();
        afterSalesRepository.createCase("case-1", "demo-user-1", "session-1", "退款");
        runRepository.createTurn(AgentTurnRecord.builder()
                .turnId("turn-1")
                .caseId("case-1")
                .sessionId("session-1")
                .actorId("demo-user-1")
                .turnType("START")
                .inputSummary("订单退款")
                .status("RUNNING")
                .startTime(now)
                .build());
        runRepository.createRun(AgentRunRecord.builder()
                .runId("run-1")
                .turnId("turn-1")
                .caseId("case-1")
                .agentId("durable-after-sales")
                .triggerType("START")
                .attemptNo(1)
                .status(AgentRunStatus.RUNNING)
                .startTime(now)
                .build());
        runRepository.createStep(AgentStepRecord.builder()
                .runId("run-1")
                .stepId("step-1")
                .stepName("INTAKE")
                .stepOrder(1)
                .stepType("AFTER_SALES_GRAPH")
                .status(AgentStepStatus.SUCCESS)
                .costMillis(1L)
                .startTime(now)
                .endTime(now)
                .build());
        AfterSalesOrderSnapshot order = afterSalesRepository
                .findOrder("ORDER-PAID-001", "demo-user-1").orElseThrow();
        Assertions.assertEquals("PAID", order.status());
        Assertions.assertEquals(AfterSalesStage.INTAKE.name(),
                afterSalesRepository.findCase("case-1").orElseThrow().stage());

        SpringStateMachineAdapter process = graph(dataSource, afterSalesRepository);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(AfterSalesAgentState.RUN_ID, "run-1");
        input.put(AfterSalesAgentState.CASE_ID, "case-1");
        input.put(AfterSalesAgentState.USER_ID, "demo-user-1");
        input.put(AfterSalesAgentState.SESSION_ID, "session-1");
        input.put(AfterSalesAgentState.ORDER_ID, "ORDER-PAID-001");
        input.put(AfterSalesAgentState.ORDER_OWNER_ID, "demo-user-1");
        input.put(AfterSalesAgentState.ORDER_STATUS, "PAID");
        input.put(AfterSalesAgentState.REFUND_REASON, "DAMAGED");

        AfterSalesAgentState waiting = process.execute(input, "case-1");
        String checkpointId = process.currentSnapshot("case-1").orElseThrow().checkpointId();
        Assertions.assertEquals(AfterSalesStage.PENDING_APPROVAL, waiting.stage());

        AfterSalesAgentState completed = process.resume(
                Map.of(AfterSalesAgentState.APPROVAL_DECISION, "APPROVE"),
                "case-1",
                checkpointId
        );

        Assertions.assertEquals(AfterSalesStage.COMPLETED, completed.stage());
        AfterSalesRefundResult replay = afterSalesRepository.executeRefund(
                "case-1", "ORDER-PAID-001", "demo-user-1", "case-1:REFUND");
        Assertions.assertTrue(replay.success());
        Assertions.assertTrue(replay.idempotentReplay());

        AfterSalesOutboxMapper outboxMapper = sqlSessionTemplate.getMapper(AfterSalesOutboxMapper.class);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        AtomicInteger handled = new AtomicInteger();
        IAfterSalesEventHandler handler = new IAfterSalesEventHandler() {
            @Override
            public boolean supports(String eventType) {
                return "REFUND_SUCCEEDED".equals(eventType);
            }

            @Override
            public void handle(AfterSalesDomainEvent event) {
                handled.incrementAndGet();
            }
        };
        IdempotentLocalEventPublisher localPublisher = new IdempotentLocalEventPublisher(
                outboxMapper, java.util.List.of(handler), transactionTemplate);
        AfterSalesOutboxDispatcher dispatcher = new AfterSalesOutboxDispatcher(
                outboxMapper, localPublisher, false, 3);
        LocalDateTime dispatchAt = LocalDateTime.now().plusSeconds(1);
        Assertions.assertEquals(1, dispatcher.dispatchBatch(10, dispatchAt).delivered());

        Map<String, Object> deliveredEvent = jdbc.queryForMap(
                "SELECT event_id, aggregate_id, event_type, payload FROM after_sales_outbox WHERE aggregate_id = ?",
                "case-1");
        localPublisher.publish(new AfterSalesDomainEvent(
                String.valueOf(deliveredEvent.get("event_id")),
                String.valueOf(deliveredEvent.get("aggregate_id")),
                String.valueOf(deliveredEvent.get("event_type")),
                String.valueOf(deliveredEvent.get("payload"))));
        Assertions.assertEquals(1, handled.get(), "duplicate event must not repeat the consumer side effect");

        outboxMapper.insertIgnore(AfterSalesOutboxPO.builder()
                .eventId("event-retry-1")
                .aggregateId("case-1")
                .eventType("REFUND_SUCCEEDED")
                .payload("{\"caseId\":\"case-1\",\"commandId\":\"retry-command\"}")
                .status("PENDING")
                .build());
        AtomicInteger publishAttempts = new AtomicInteger();
        AfterSalesOutboxDispatcher retryDispatcher = new AfterSalesOutboxDispatcher(
                outboxMapper,
                event -> {
                    if (publishAttempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("injected publish failure");
                    }
                },
                false,
                3);
        Assertions.assertEquals(1, retryDispatcher.dispatchBatch(10, dispatchAt.plusSeconds(1)).retried());
        Assertions.assertEquals(1, retryDispatcher.dispatchBatch(10, dispatchAt.plusSeconds(3)).delivered());
        Assertions.assertEquals(2, publishAttempts.get());

        assertRowCount(jdbc, "agent_run", 1);
        assertRowCount(jdbc, "agent_step", 1);
        assertRowCount(jdbc, "agent_turn", 1);
        assertRowCount(jdbc, "demo_order", 3);
        assertRowCount(jdbc, "after_sales_case", 1);
        assertRowCount(jdbc, "refund_command", 1);
        assertRowCount(jdbc, "after_sales_outbox", 2);
        assertRowCount(jdbc, "after_sales_event_consume", 1);
        Assertions.assertEquals("REFUNDED", afterSalesRepository
                .findOrder("ORDER-PAID-001", "demo-user-1").orElseThrow().status());
    }

    private MysqlDataSource dataSource() {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }

    private SpringStateMachineAdapter graph(MysqlDataSource dataSource,
                                            AfterSalesRepository repository) throws Exception {
        return new SpringStateMachineAdapter(
                new UnusedToolPort(),
                repository,
                new RefundPlanningAgent(null),
                new RefundInformationGatheringPolicy(),
                null);
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
        public AfterSalesToolRequest proposeOrderQuery(String userMessage, String userId, String sessionId,
                                                       String orderIdHint, String refundReason, String correction) {
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
