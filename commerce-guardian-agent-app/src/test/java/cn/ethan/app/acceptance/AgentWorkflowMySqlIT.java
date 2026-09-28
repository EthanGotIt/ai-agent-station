package cn.ethan.app.acceptance;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointModel;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStore;
import cn.ethan.core.agent.workflow.AgentWorkflowDecisionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowOrchestrationVersionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.infrastructure.agent.action.persistence.ExternalActionCommandMapper;
import cn.ethan.infrastructure.agent.action.persistence.MybatisExternalActionCommandStore;
import cn.ethan.infrastructure.agent.thread.persistence.AgentThreadMapper;
import cn.ethan.infrastructure.agent.thread.persistence.AgentWorkflowRunMapper;
import cn.ethan.infrastructure.agent.thread.persistence.MybatisAgentWorkflowRunStore;
import cn.ethan.infrastructure.agent.workflow.persistence.AgentWorkflowCheckpointMapper;
import cn.ethan.infrastructure.agent.workflow.persistence.MybatisAgentWorkflowCheckpointStore;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：使用生产 MyBatis Store 和真实事务验证催发货授权、命令幂等及版本竞争。
 *
 * @author ethan
 * @date 2026-09-06
 */
@EnabledIfSystemProperty(named = "commerceGuardianWorkflowAcceptance", matches = "true")
class AgentWorkflowMySqlIT {

    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    private static final String USER_ID = "workflow-it-user";
    private static DataSource dataSource;
    private static String schema;
    private static String serverUrl;

    @BeforeAll
    static void prepareDatabase() throws Exception {
        String configuredUrl = configured("MYSQL_URL",
                "jdbc:mysql://127.0.0.1:3306/COMMERCE_GUARDIAN_AGENT"
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false"
                        + "&allowPublicKeyRetrieval=true");
        serverUrl = withoutDatabase(configuredUrl);
        schema = "CGA_WORKFLOW_IT_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String username = configured("MYSQL_USERNAME", "root");
        String password = configured("MYSQL_PASSWORD", "");
        try (Connection connection = dataSource(serverUrl, username, password).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String targetUrl = withDatabase(serverUrl, schema);
        dataSource = dataSource(targetUrl, username, password);
        executeBaseline(dataSource);
        prepareV9Schema(dataSource);
        Flyway.configure().dataSource(targetUrl, username, password).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("9").target("9").load().migrate();
        Flyway.configure().dataSource(targetUrl, username, password).locations("classpath:db/migration")
                .load().migrate();
        insertThread("thread-rollback");
        insertThread("thread-command");
        insertThread("thread-cas");
        insertThread("thread-outcome");
        insertThread("thread-java-approval");
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        if (serverUrl == null || schema == null) {
            return;
        }
        String username = configured("MYSQL_USERNAME", "root");
        String password = configured("MYSQL_PASSWORD", "");
        try (Connection connection = dataSource(serverUrl, username, password).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
        }
    }

    @Test
    void javaWorkflowOrchestrationVersionRoundTripsWithoutSnapshotVersionGuessing() {
        try (AnnotationConfigApplicationContext context = persistenceContext()) {
            AgentWorkflowRunStore runs = context.getBean(AgentWorkflowRunStore.class);
            AgentWorkflowRunModel run = run("run-java-version", "thread-cas", "turn-java-version",
                    AgentWorkflowOrchestrationVersionEnum.EXPEDITE_JAVA_V1);

            runs.create(run);

            assertEquals(AgentWorkflowOrchestrationVersionEnum.EXPEDITE_JAVA_V1,
                    runs.findBySource(USER_ID, "turn-java-version", AgentWorkflowTypeEnum.ORDER_SERVICE)
                            .orElseThrow().orchestrationVersion());
        }
    }

    @Test
    void flywayV10ToV13AndManagedStoresPreserveVersionAndOwnership() throws Exception {
        try (AnnotationConfigApplicationContext context = persistenceContext()) {
            AgentWorkflowRunStore runs = context.getBean(AgentWorkflowRunStore.class);
            AgentWorkflowRunModel run = run("run-version", "thread-cas", "turn-version",
                    AgentWorkflowOrchestrationVersionEnum.EXPEDITE_GRAPH_V1);
            runs.create(run);
            assertEquals(AgentWorkflowOrchestrationVersionEnum.EXPEDITE_GRAPH_V1,
                    runs.findBySource(USER_ID, "turn-version", AgentWorkflowTypeEnum.ORDER_SERVICE)
                            .orElseThrow().orchestrationVersion());
            assertTrue(runs.find("other-user", run.runId()).isEmpty());
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND UPPER(TABLE_NAME) = 'FLYWAY_SCHEMA_HISTORY'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = 'AGENT_WORKFLOW_RUN' AND COLUMN_NAME = 'ORCHESTRATION_VERSION'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = 'EXTERNAL_ACTION_COMMAND' AND COLUMN_NAME = 'OUTCOME_STATUS'"));
        }
        Flyway migrated = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        assertEquals("13", migrated.info().current().getVersion().getVersion());
    }

    @Test
    void confirmationTransactionRollsBackRunCheckpointAndThreadPointer() {
        try (AnnotationConfigApplicationContext context = persistenceContext()) {
            AgentWorkflowRunStore runs = context.getBean(AgentWorkflowRunStore.class);
            AgentWorkflowCheckpointStore checkpoints = context.getBean(AgentWorkflowCheckpointStore.class);
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            AgentWorkflowRunModel run = run("run-rollback", "thread-rollback", "turn-rollback",
                    AgentWorkflowOrchestrationVersionEnum.EXPEDITE_GRAPH_V1);
            AgentWorkflowCheckpointModel checkpoint = checkpoint(run);

            assertThrowsRollback(() -> transaction.executeWithoutResult(status -> {
                runs.create(run);
                checkpoints.create(checkpoint);
                throw new RollbackMarker();
            }));

            assertTrue(runs.find(USER_ID, run.runId()).isEmpty());
            assertTrue(checkpoints.find(USER_ID, checkpoint.checkpointId()).isEmpty());
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                    "SELECT OPEN_INTERACTION_ID FROM AGENT_THREAD WHERE THREAD_ID = ?")) {
                statement.setString(1, run.threadId());
                try (var result = statement.executeQuery()) {
                    result.next();
                    assertNull(result.getString(1));
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    @Test
    void approvalCommandIsIdempotentAndStaleRunVersionHasOneWinner() throws Exception {
        try (AnnotationConfigApplicationContext context = persistenceContext()) {
            AgentWorkflowRunStore runs = context.getBean(AgentWorkflowRunStore.class);
            AgentWorkflowCheckpointStore checkpoints = context.getBean(AgentWorkflowCheckpointStore.class);
            ExternalActionCommandStore commands = context.getBean(ExternalActionCommandStore.class);
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            AgentWorkflowRunModel run = run("run-command", "thread-command", "turn-command",
                    AgentWorkflowOrchestrationVersionEnum.EXPEDITE_GRAPH_V1);
            AgentWorkflowCheckpointModel checkpoint = checkpoint(run);
            transaction.executeWithoutResult(status -> {
                runs.create(run);
                checkpoints.create(checkpoint);
            });
            assertTrue(checkpoints.decide(USER_ID, checkpoint.checkpointId(), 0,
                    AgentWorkflowDecisionEnum.APPROVE, checkpoint.factsFingerprint()));

            AgentWorkflowRunModel stale = runs.find(USER_ID, run.runId()).orElseThrow();
            AgentWorkflowRunModel first = stale.progress("[{\"node\":\"EXECUTE_ACTION\"}]", "{}", NOW);
            AgentWorkflowRunModel second = stale.progress("[{\"node\":\"EXECUTE_ACTION\"}]", "{}", NOW.plusSeconds(1));
            CountDownLatch ready = new CountDownLatch(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            Future<Boolean> winnerOne = executor.submit(() -> tryUpdate(runs, transaction, first, ready));
            Future<Boolean> winnerTwo = executor.submit(() -> tryUpdate(runs, transaction, second, ready));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(winnerOne.get(10, TimeUnit.SECONDS) ^ winnerTwo.get(10, TimeUnit.SECONDS));
            executor.shutdownNow();

            ExternalActionCommandModel command = command(run);
            ExternalActionCommandModel firstCommand = commands.createIfAbsent(command);
            ExternalActionCommandModel repeatedCommand = commands.createIfAbsent(command(
                    run, "different-command-id"));
            assertEquals(firstCommand.commandId(), repeatedCommand.commandId());
            assertEquals(1L, count("SELECT COUNT(*) FROM EXTERNAL_ACTION_COMMAND WHERE RUN_ID = 'run-command'"));
        }
    }

    @Test
    void concurrentJavaCheckpointApprovalsHaveOneWinner() throws Exception {
        try (AnnotationConfigApplicationContext context = persistenceContext()) {
            AgentWorkflowRunStore runs = context.getBean(AgentWorkflowRunStore.class);
            AgentWorkflowCheckpointStore checkpoints = context.getBean(AgentWorkflowCheckpointStore.class);
            AgentWorkflowRunModel run = run("run-java-approval", "thread-java-approval", "turn-java-approval",
                    AgentWorkflowOrchestrationVersionEnum.EXPEDITE_JAVA_V1);
            AgentWorkflowCheckpointModel checkpoint = checkpoint(run);
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            transaction.executeWithoutResult(status -> {
                runs.create(run);
                checkpoints.create(checkpoint);
            });

            CountDownLatch ready = new CountDownLatch(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            Future<Boolean> first = executor.submit(() -> tryDecide(checkpoints, transaction, checkpoint, ready));
            Future<Boolean> second = executor.submit(() -> tryDecide(checkpoints, transaction, checkpoint, ready));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS));
            executor.shutdownNow();

            assertEquals(1L, count("SELECT COUNT(*) FROM AGENT_WORKFLOW_CHECKPOINT "
                    + "WHERE CHECKPOINT_ID = 'checkpoint-run-java-approval' AND STATUS = 'APPROVED'"));
        }
    }

    @Test
    void persistsUnknownOutcomeAndIndependentVerificationBudgetAcrossSessions() {
        try (AnnotationConfigApplicationContext context = persistenceContext()) {
            ExternalActionCommandStore commands = context.getBean(ExternalActionCommandStore.class);
            AgentWorkflowRunModel run = run("run-outcome", "thread-outcome", "turn-outcome",
                    AgentWorkflowOrchestrationVersionEnum.EXPEDITE_GRAPH_V1);
            ExternalActionCommandModel created = commands.createIfAbsent(command(run, "command-outcome"));

            ExternalActionCommandModel claimed = commands.claimDue(
                            NOW, NOW.plusSeconds(30), "worker-outcome", 100).stream()
                    .filter(candidate -> candidate.commandId().equals(created.commandId()))
                    .findFirst().orElseThrow();
            ExternalActionCommandModel unknown = claimed.outcomeUnknownAt(
                    NOW.plusSeconds(5), "REMOTE_TIMEOUT", "响应丢失", NOW);
            assertTrue(commands.update(claimed, unknown));

            ExternalActionCommandModel reloaded = commands.findByRunId(USER_ID, run.runId()).orElseThrow();
            assertEquals(ExternalActionStatusEnum.VERIFY_WAIT, reloaded.status());
            assertEquals(cn.ethan.core.agent.action.ExternalActionOutcomeEnum.UNKNOWN, reloaded.outcome());
            assertEquals(created.maxVerificationAttempts(), reloaded.maxVerificationAttempts());

            ExternalActionCommandModel verification = commands.claimDue(
                            NOW.plusSeconds(10), NOW.plusSeconds(40), "worker-verification", 100).stream()
                    .filter(candidate -> candidate.commandId().equals(created.commandId()))
                    .findFirst().orElseThrow();
            assertEquals(created.commandId(), verification.commandId());
            assertEquals(ExternalActionStatusEnum.PROCESSING, verification.status());
            assertEquals(1, verification.retryCycleAttemptCount(), "核验重放不占用明确失败重试预算");
            assertEquals(cn.ethan.core.agent.action.ExternalActionOutcomeEnum.UNKNOWN, verification.outcome());
        }
    }

    private static boolean tryUpdate(AgentWorkflowRunStore runs, TransactionTemplate transaction,
                                     AgentWorkflowRunModel candidate, CountDownLatch ready) {
        ready.countDown();
        try {
            ready.await(5, TimeUnit.SECONDS);
            transaction.executeWithoutResult(status -> runs.update(candidate));
            return true;
        } catch (RuntimeException | InterruptedException failure) {
            return false;
        }
    }

    private static boolean tryDecide(AgentWorkflowCheckpointStore checkpoints, TransactionTemplate transaction,
                                    AgentWorkflowCheckpointModel checkpoint, CountDownLatch ready) {
        ready.countDown();
        try {
            ready.await(5, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(transaction.execute(status -> checkpoints.decide(USER_ID,
                    checkpoint.checkpointId(), checkpoint.version(), AgentWorkflowDecisionEnum.APPROVE,
                    checkpoint.factsFingerprint())));
        } catch (RuntimeException | InterruptedException failure) {
            return false;
        }
    }

    private static AnnotationConfigApplicationContext persistenceContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(PersistenceTestConfiguration.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class PersistenceTestConfiguration {
        @Bean DataSource dataSource() { return AgentWorkflowMySqlIT.dataSource; }
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) { return springFactory(source); }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean AgentThreadMapper agentThreadMapper(SqlSessionTemplate template) { return template.getMapper(AgentThreadMapper.class); }
        @Bean AgentWorkflowRunMapper agentWorkflowRunMapper(SqlSessionTemplate template) { return template.getMapper(AgentWorkflowRunMapper.class); }
        @Bean AgentWorkflowCheckpointMapper agentWorkflowCheckpointMapper(SqlSessionTemplate template) { return template.getMapper(AgentWorkflowCheckpointMapper.class); }
        @Bean ExternalActionCommandMapper externalActionCommandMapper(SqlSessionTemplate template) { return template.getMapper(ExternalActionCommandMapper.class); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean AgentWorkflowRunStore workflowRuns(AgentWorkflowRunMapper mapper) { return new MybatisAgentWorkflowRunStore(mapper); }
        @Bean AgentWorkflowCheckpointStore checkpoints(AgentWorkflowCheckpointMapper mapper, AgentThreadMapper threads) {
            return new MybatisAgentWorkflowCheckpointStore(mapper, threads);
        }
        @Bean ExternalActionCommandStore commands(ExternalActionCommandMapper mapper) {
            return new MybatisExternalActionCommandStore(mapper);
        }
    }

    private static AgentWorkflowRunModel run(String runId, String threadId, String turnId,
                                             AgentWorkflowOrchestrationVersionEnum version) {
        return new AgentWorkflowRunModel(runId, threadId, turnId, USER_ID, AgentWorkflowTypeEnum.ORDER_SERVICE,
                AgentWorkflowStatusEnum.WAITING_USER_INPUT, 0L, "[]",
                "{\"intent\":\"EXPEDITE\",\"orderId\":\"ORDER-1\"}", NOW, NOW, version);
    }

    private static AgentWorkflowCheckpointModel checkpoint(AgentWorkflowRunModel run) {
        return new AgentWorkflowCheckpointModel("checkpoint-" + run.runId(), run.runId(), run.threadId(),
                run.turnId(), USER_ID, "SWITCH_REQUIREMENTS", "EXPEDITE", "ORDER-1", "催发货",
                "expedite-facts-v1:fingerprint", 0L, AgentWorkflowCheckpointStatusEnum.OPEN, null, NOW, null);
    }

    private static ExternalActionCommandModel command(AgentWorkflowRunModel run) {
        return command(run, "command-" + run.runId());
    }

    private static ExternalActionCommandModel command(AgentWorkflowRunModel run, String commandId) {
        return new ExternalActionCommandModel(commandId, run.runId(), run.threadId(), run.turnId(), USER_ID,
                ExternalActionTypeEnum.EXPEDITE, "order-service:" + run.runId(),
                "{\"orderId\":\"ORDER-1\"}", ExternalActionStatusEnum.PENDING, 0, 3,
                NOW, null, null, null, null, NOW, NOW, null);
    }

    private static void assertThrowsRollback(Runnable action) {
        try {
            action.run();
        } catch (RollbackMarker expected) {
            return;
        }
        throw new AssertionError("事务应当回滚");
    }

    private static void insertThread(String threadId) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO AGENT_THREAD (THREAD_ID, USER_ID, TITLE, STATUS, NEXT_SEQUENCE, CREATED_AT, UPDATED_AT) "
                        + "VALUES (?, ?, 'workflow IT', 'ACTIVE', 1, ?, ?)")) {
            statement.setString(1, threadId);
            statement.setString(2, USER_ID);
            statement.setObject(3, NOW);
            statement.setObject(4, NOW);
            statement.executeUpdate();
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            return scalar(statement, sql);
        }
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void prepareV9Schema(DataSource source) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE AGENT_CONTEXT_SNAPSHOT DROP INDEX IDX_AGENT_CONTEXT_SNAPSHOT_BASE");
            statement.execute("ALTER TABLE AGENT_CONTEXT_SNAPSHOT DROP COLUMN SUMMARY_MAX_OUTPUT_TOKENS, "
                    + "DROP COLUMN SUMMARY_PROMPT_VERSION, DROP COLUMN SOURCE_ESTIMATED_TOKENS, "
                    + "DROP COLUMN SOURCE_FROM_SEQUENCE, DROP COLUMN BASE_SNAPSHOT_ID, DROP COLUMN FORMAT_VERSION");
            statement.execute("ALTER TABLE AGENT_WORKFLOW_RUN DROP COLUMN ORCHESTRATION_VERSION");
            statement.execute("ALTER TABLE AGENT_GRAPH_SNAPSHOT DROP COLUMN ORCHESTRATION_VERSION");
            statement.execute("ALTER TABLE EXTERNAL_ACTION_COMMAND DROP COLUMN OUTCOME_STATUS, "
                    + "DROP COLUMN VERIFICATION_ATTEMPT_COUNT, DROP COLUMN MAX_VERIFICATION_ATTEMPTS");
        }
    }

    private static void executeBaseline(DataSource source) throws IOException, SQLException {
        Path baseline = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (baseline != null && !Files.isRegularFile(baseline.resolve("docs/dev-ops/mysql/commerce-guardian-agent.sql"))) {
            baseline = baseline.getParent();
        }
        if (baseline == null) {
            throw new IllegalStateException("找不到 MySQL 基线脚本");
        }
        String script = Files.readString(baseline.resolve("docs/dev-ops/mysql/commerce-guardian-agent.sql"));
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            for (String raw : script.split(";")) {
                String sql = raw.replaceAll("(?m)--.*$", "").trim();
                String upper = sql.toUpperCase(Locale.ROOT);
                if (sql.isEmpty() || upper.startsWith("CREATE DATABASE") || upper.startsWith("USE ")
                        || upper.startsWith("SET FOREIGN_KEY_CHECKS")) {
                    continue;
                }
                statement.execute(sql);
            }
        }
    }

    private static SqlSessionFactory springFactory(DataSource source) {
        Environment environment = new Environment("workflow-it", new SpringManagedTransactionFactory(), source);
        MybatisConfiguration configuration = new MybatisConfiguration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentThreadMapper.class);
        configuration.addMapper(AgentWorkflowRunMapper.class);
        configuration.addMapper(AgentWorkflowCheckpointMapper.class);
        configuration.addMapper(ExternalActionCommandMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private static DataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    private static String configured(String name, String fallback) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) return value;
        Path env = locateModuleEnv();
        if (Files.isRegularFile(env)) {
            try {
                for (String line : Files.readAllLines(env)) {
                    String trimmed = line.strip();
                    if (trimmed.startsWith(name + "=")) {
                        String result = trimmed.substring(name.length() + 1).strip();
                        return result.replaceAll("^['\"]|['\"]$", "");
                    }
                }
            } catch (IOException failure) {
                throw new IllegalStateException("无法读取模块 .env 测试配置", failure);
            }
        }
        return fallback;
    }

    private static Path locateModuleEnv() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path local = current.resolve(".env");
            if (Files.isRegularFile(local)) {
                return local;
            }
            Path module = current.resolve("commerce-guardian-agent-app").resolve(".env");
            if (Files.isRegularFile(module)) {
                return module;
            }
            current = current.getParent();
        }
        return Path.of(".env");
    }

    private static String withoutDatabase(String url) {
        int scheme = url.indexOf("://");
        int slash = url.indexOf('/', scheme + 3);
        int query = url.indexOf('?', slash);
        return url.substring(0, slash + 1) + (query < 0 ? "" : url.substring(query));
    }

    private static String withDatabase(String url, String database) {
        int slash = url.indexOf('/', url.indexOf("://") + 3);
        int query = url.indexOf('?', slash);
        return url.substring(0, slash + 1) + database + (query < 0 ? "" : url.substring(query));
    }

    private static final class RollbackMarker extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
