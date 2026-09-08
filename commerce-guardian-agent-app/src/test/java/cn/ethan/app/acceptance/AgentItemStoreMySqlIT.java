package cn.ethan.app.acceptance;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.context.AgentContextSnapshotModel;
import cn.ethan.core.agent.context.AgentContextSnapshotStore;
import cn.ethan.core.agent.context.AgentContextTokenEstimator;
import cn.ethan.core.agent.context.AgentContextAssembler;
import cn.ethan.core.agent.context.AgentContextAssembly;
import cn.ethan.core.agent.context.AgentContextSummaryGateway;
import cn.ethan.core.agent.context.AgentContextCompactionSettings;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowOrchestrationVersionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.infrastructure.agent.thread.persistence.AgentItemMapper;
import cn.ethan.infrastructure.agent.thread.persistence.AgentContextSnapshotMapper;
import cn.ethan.infrastructure.agent.thread.persistence.AgentThreadMapper;
import cn.ethan.infrastructure.agent.thread.persistence.AgentThreadEntity;
import cn.ethan.infrastructure.agent.thread.persistence.MybatisAgentContextSnapshotStore;
import cn.ethan.infrastructure.agent.thread.persistence.MybatisAgentItemStore;
import cn.ethan.infrastructure.agent.thread.persistence.AgentWorkflowRunMapper;
import cn.ethan.infrastructure.agent.thread.persistence.MybatisAgentWorkflowRunStore;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：使用真实 MySQL 和 MyBatis 验证固定水位的完整 Item 历史读取。
 *
 * @author ethan
 * @date 2026-09-05
 */
@EnabledIfSystemProperty(named = "commerceGuardianContextAcceptance", matches = "true")
class AgentItemStoreMySqlIT {

    private static final String USER_ID = "context-it-user";
    private static final String THREAD_ID = "context-it-thread";
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    private static DataSource dataSource;
    private static String schema;
    private static String serverUrl;

    @BeforeAll
    static void prepareDatabase() throws Exception {
        String configuredUrl = value("MYSQL_URL",
                "jdbc:mysql://127.0.0.1:3306/COMMERCE_GUARDIAN_AGENT"
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false"
                        + "&allowPublicKeyRetrieval=true");
        serverUrl = withoutDatabase(configuredUrl);
        schema = "CGA_CONTEXT_IT_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String username = value("MYSQL_USERNAME", "root");
        String password = System.getenv().getOrDefault("MYSQL_PASSWORD", "");
        try (Connection connection = DriverManagerDataSourceBuilder.connection(serverUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String targetUrl = withDatabase(serverUrl, schema);
        dataSource = DriverManagerDataSourceBuilder.dataSource(targetUrl, username, password);
        executeBaseline(dataSource);
        prepareV9Schema(dataSource);
        migrateToV9(dataSource, targetUrl, username, password);
        insertThread(dataSource);
        insertLegacySnapshot(dataSource);
        migrateToV11(targetUrl, username, password);
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        if (schema == null || serverUrl == null) {
            return;
        }
        String username = value("MYSQL_USERNAME", "root");
        String password = System.getenv().getOrDefault("MYSQL_PASSWORD", "");
        try (Connection connection = DriverManagerDataSourceBuilder.connection(serverUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
        }
    }

    @Test
    void readsAllPagesThroughFixedWatermarkAndSurvivesNewSession() throws Exception {
        SqlSessionFactory factory = factory(dataSource);
        try (SqlSession session = factory.openSession(true)) {
            AgentItemStore store = new MybatisAgentItemStore(
                    session.getMapper(AgentItemMapper.class), session.getMapper(AgentThreadMapper.class));
            for (int index = 1; index <= 601; index++) {
                store.appendItem(item(index, "历史目标 " + index));
            }

            long watermark = store.captureWatermark(USER_ID, THREAD_ID);
            assertEquals(601L, watermark);
            List<AgentItemModel> firstPage = store.listItemsThrough(USER_ID, THREAD_ID, 0L, watermark, 300);
            assertEquals(300, firstPage.size());
            assertEquals(1L, firstPage.get(0).sequence());
            assertEquals(300L, firstPage.get(299).sequence());

            store.appendItem(item(602, "水位之后的事实"));
            List<AgentItemModel> secondPage = store.listItemsThrough(USER_ID, THREAD_ID, 300L, watermark, 300);
            assertEquals(300, secondPage.size());
            assertEquals(301L, secondPage.get(0).sequence());
            assertEquals(600L, secondPage.get(299).sequence());
            assertTrue(secondPage.stream().noneMatch(value -> value.sequence() > watermark));
            List<AgentItemModel> lastPage = store.listItemsThrough(USER_ID, THREAD_ID, 600L, watermark, 300);
            assertEquals(1, lastPage.size());
            assertEquals(601L, lastPage.get(0).sequence());

            assertEquals(0L, store.captureWatermark("another-user", THREAD_ID));
            assertEquals(List.of(), store.listItemsThrough("another-user", THREAD_ID, 0L, watermark, 300));
        }

        try (SqlSession session = factory(dataSource).openSession(true)) {
            AgentItemStore store = new MybatisAgentItemStore(
                    session.getMapper(AgentItemMapper.class), session.getMapper(AgentThreadMapper.class));
            long watermark = store.captureWatermark(USER_ID, THREAD_ID);
            assertEquals(602L, watermark);
            List<AgentItemModel> page = store.listItemsThrough(USER_ID, THREAD_ID, 600L, watermark, 300);
            assertEquals(2, page.size());
            assertEquals(List.of(601L, 602L), page.stream().map(AgentItemModel::sequence).toList());
        }
    }

    @Test
    void persistsAndReadsWorkflowOrchestrationVersionByOwnedSource() {
        String runId = "workflow-version-it";
        AgentWorkflowRunModel run = new AgentWorkflowRunModel(
                runId, THREAD_ID, "turn-version-it", USER_ID, AgentWorkflowTypeEnum.ORDER_SERVICE,
                cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum.WAITING_USER_INPUT, 0L,
                "[]", "{\"intent\":\"EXPEDITE\",\"orderId\":\"ORDER-PAID-001\"}",
                NOW, NOW, AgentWorkflowOrchestrationVersionEnum.EXPEDITE_GRAPH_V1);
        try (SqlSession session = factory(dataSource).openSession(true)) {
            MybatisAgentWorkflowRunStore store = new MybatisAgentWorkflowRunStore(
                    session.getMapper(AgentWorkflowRunMapper.class));
            store.create(run);
            assertEquals(AgentWorkflowOrchestrationVersionEnum.EXPEDITE_GRAPH_V1,
                    store.findBySource(USER_ID, "turn-version-it", AgentWorkflowTypeEnum.ORDER_SERVICE)
                            .orElseThrow().orchestrationVersion());
        }
    }

    @Test
    void preservesV9RowsAndEnforcesSnapshotOwnershipAndCas() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertEquals(1L, scalar(statement, "SELECT COUNT(*) FROM AGENT_CONTEXT_SNAPSHOT WHERE SNAPSHOT_ID = 'legacy-v9'"));
            assertEquals(1L, scalar(statement, "SELECT FORMAT_VERSION FROM AGENT_CONTEXT_SNAPSHOT WHERE SNAPSHOT_ID = 'legacy-v9'"));
            assertEquals(1L, scalar(statement, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND UPPER(TABLE_NAME) = 'FLYWAY_SCHEMA_HISTORY'"));
            assertEquals(1L, scalar(statement, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND UPPER(TABLE_NAME) = 'AGENT_CONTEXT_SNAPSHOT' "
                    + "AND UPPER(COLUMN_NAME) = 'SUMMARY_MAX_OUTPUT_TOKENS'"));
            assertTrue(scalar(statement, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'AGENT_CONTEXT_SNAPSHOT' AND COLUMN_NAME = 'BASE_SNAPSHOT_ID'") == 1L);
            assertEquals(1L, scalar(statement, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND UPPER(TABLE_NAME) = 'AGENT_WORKFLOW_RUN' AND UPPER(COLUMN_NAME) = 'ORCHESTRATION_VERSION'"));
        }
        Flyway migrated = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        assertEquals("11", migrated.info().current().getVersion().getVersion());

        AgentContextSnapshotModel candidate = snapshot("cas-1", 2L, 1L, "摘要事实");
        AgentContextSnapshotModel otherOwner = new AgentContextSnapshotModel(
                "cas-other", "other-thread", 2L, 1L,
                candidate.estimatedTokens(), candidate.summary(), NOW, 2, null,
                1L, candidate.sourceEstimatedTokens(), "context-summary-v2", 2_048);
        try (SqlSession session = factory(dataSource).openSession(true)) {
            AgentContextSnapshotStore store = new MybatisAgentContextSnapshotStore(
                    session.getMapper(AgentContextSnapshotMapper.class), session.getMapper(AgentThreadMapper.class));
            assertTrue(store.saveSnapshotIfCurrent(USER_ID, THREAD_ID, "legacy-v9", candidate));
            assertTrue(!store.saveSnapshotIfCurrent(USER_ID, THREAD_ID, "legacy-v9", snapshot(
                    "cas-loser", 3L, 2L, "另一个摘要")));
            assertTrue(!store.saveSnapshotIfCurrent("another-user", THREAD_ID, "cas-1", otherOwner));
            assertEquals("cas-1", store.findSnapshot(USER_ID, THREAD_ID, "cas-1").orElseThrow().snapshotId());
            assertTrue(store.findSnapshot("another-user", THREAD_ID, "cas-1").isEmpty());
        }
    }

    @Test
    void concurrentTransactionsAllowOnlyOneCasWinner() throws Exception {
        var locksRequested = new java.util.concurrent.CountDownLatch(2);
        AgentContextSnapshotStore store = transactionalSnapshotStore(locksRequested);
        String expectedSnapshotId = store.findLatestSnapshot(USER_ID, THREAD_ID).orElseThrow().snapshotId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        try (Connection blocker = dataSource.getConnection();
             PreparedStatement lock = blocker.prepareStatement(
                     "SELECT THREAD_ID FROM AGENT_THREAD WHERE THREAD_ID = ? FOR UPDATE")) {
            blocker.setAutoCommit(false);
            lock.setString(1, THREAD_ID);
            lock.executeQuery().close();
            Future<Boolean> first = executor.submit(() -> {
                ready.countDown();
                ready.await(5, TimeUnit.SECONDS);
                return store.saveSnapshotIfCurrent(USER_ID, THREAD_ID, expectedSnapshotId,
                        snapshot("cas-concurrent-" + UUID.randomUUID(), 3L, 999L, "并发摘要一"));
            });
            Future<Boolean> second = executor.submit(() -> {
                ready.countDown();
                ready.await(5, TimeUnit.SECONDS);
                return store.saveSnapshotIfCurrent(USER_ID, THREAD_ID, expectedSnapshotId,
                        snapshot("cas-concurrent-" + UUID.randomUUID(), 3L, 999L, "并发摘要二"));
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            // 代理在两个生产 Store 事务进入 SELECT FOR UPDATE 前计数，外部锁释放后才允许竞争继续。
            assertTrue(locksRequested.await(5, TimeUnit.SECONDS));
            blocker.commit();
            assertTrue(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM AGENT_CONTEXT_SNAPSHOT WHERE THREAD_ID = ? AND SNAPSHOT_ID LIKE 'cas-concurrent-%'")) {
            statement.setString(1, THREAD_ID);
            try (var result = statement.executeQuery()) {
                result.next();
                assertEquals(1L, result.getLong(1));
            }
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM AGENT_CONTEXT_SNAPSHOT WHERE SNAPSHOT_ID LIKE 'cas-concurrent-%'");
        }
    }

    @Test
    void recreatesApplicationContextForValidRecoveryAndInvalidChainRebuild() throws Exception {
        String userId = "restart-user-" + UUID.randomUUID();
        String threadId = "restart-thread-" + UUID.randomUUID();
        insertThread(dataSource, userId, threadId);
        try {
            AgentContextSnapshotModel valid = new AgentContextSnapshotModel(
                    "restart-valid", threadId, 1L, 1L,
                    AgentContextTokenEstimator.estimateText("重启后有效摘要"), "重启后有效摘要", NOW,
                    2, null, 1L, AgentContextTokenEstimator.estimateText("原始事实"),
                    "context-summary-v2", 2_048);
            try (AnnotationConfigApplicationContext first = persistenceContext()) {
                AgentItemStore store = first.getBean(AgentItemStore.class);
                AgentContextSnapshotStore snapshots = first.getBean(AgentContextSnapshotStore.class);
                assertEquals(1L, store.appendItem(item(threadId, 1L, "原始事实")));
                assertTrue(snapshots.saveSnapshotIfCurrent(userId, threadId, null, valid));
            }

            try (AnnotationConfigApplicationContext second = persistenceContext()) {
                try (Connection connection = dataSource.getConnection();
                     PreparedStatement statement = connection.prepareStatement(
                             "SELECT SUMMARY_PROMPT_VERSION FROM AGENT_CONTEXT_SNAPSHOT WHERE SNAPSHOT_ID = ?")) {
                    statement.setString(1, "restart-valid");
                    try (var result = statement.executeQuery()) {
                        result.next();
                        assertEquals("context-summary-v2", result.getString(1));
                    }
                }
                AgentContextSnapshotModel stored = second.getBean(AgentContextSnapshotStore.class)
                        .findLatestSnapshot(userId, threadId).orElseThrow();
                assertTrue(stored.validV2(), "重建前读取的 V2 快照必须有效：" + stored);
                AgentContextAssembler assembler = second.getBean(AgentContextAssembler.class);
                AgentContextAssembly restored = assembler.assembleWithReport(
                        thread(userId, threadId), null, "继续查询");
                assertEquals("重启后有效摘要", restored.summary());
                assertTrue(restored.items().isEmpty(), "有效摘要应覆盖已经读取的原始事实");
            }

            try (AnnotationConfigApplicationContext third = persistenceContext()) {
                third.getBean(AgentContextSnapshotStore.class).saveSnapshot(
                        new AgentContextSnapshotModel(
                                "restart-invalid", threadId, 1L, 2L,
                                AgentContextTokenEstimator.estimateText("失效摘要"), "失效摘要", NOW,
                                2, "missing-base", 1L,
                                AgentContextTokenEstimator.estimateText("失效摘要"),
                                "context-summary-v2", 2_048));
            }
            try (AnnotationConfigApplicationContext fourth = persistenceContext()) {
                AgentContextAssembly rebuilt = fourth.getBean(AgentContextAssembler.class)
                        .assembleWithReport(thread(userId, threadId), null, "继续查询");
                assertEquals("", rebuilt.summary(), "无效基础链必须从原始 Items 重建");
                assertTrue(rebuilt.items().stream().anyMatch(item -> item.payload().contains("原始事实")));
            }
        } finally {
            deleteThread(dataSource, threadId);
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

        @Bean
        DataSource dataSource() {
            return AgentItemStoreMySqlIT.dataSource;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) {
            return springFactory(dataSource);
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        AgentItemMapper agentItemMapper(SqlSessionTemplate template) {
            return template.getMapper(AgentItemMapper.class);
        }

        @Bean
        AgentThreadMapper agentThreadMapper(SqlSessionTemplate template) {
            return template.getMapper(AgentThreadMapper.class);
        }

        @Bean
        AgentContextSnapshotMapper agentContextSnapshotMapper(SqlSessionTemplate template) {
            return template.getMapper(AgentContextSnapshotMapper.class);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        AgentItemStore agentItemStore(AgentItemMapper items, AgentThreadMapper threads) {
            return new MybatisAgentItemStore(items, threads);
        }

        @Bean
        AgentContextSnapshotStore agentContextSnapshotStore(
                AgentContextSnapshotMapper snapshots, AgentThreadMapper threads
        ) {
            return new MybatisAgentContextSnapshotStore(snapshots, threads);
        }

        @Bean
        AgentContextSummaryGateway agentContextSummaryGateway() {
            return (request, executionContext) -> {
                throw new AssertionError("摘要恢复测试不应重新调用摘要模型");
            };
        }

        @Bean
        AgentContextAssembler agentContextAssembler(
                AgentItemStore items,
                AgentContextSnapshotStore snapshots,
                AgentContextSummaryGateway summaryGateway
        ) {
            return new AgentContextAssembler(items, snapshots, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                    65_536, 8_000, 1_500, summaryGateway,
                    AgentContextCompactionSettings.defaults());
        }
    }

    private static AgentContextSnapshotStore transactionalSnapshotStore(
            java.util.concurrent.CountDownLatch locksRequested
    ) {
        SqlSessionFactory factory = springFactory(dataSource);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        AgentThreadMapper delegate = template.getMapper(AgentThreadMapper.class);
        AgentThreadMapper observed = (AgentThreadMapper) Proxy.newProxyInstance(
                AgentThreadMapper.class.getClassLoader(),
                new Class<?>[]{AgentThreadMapper.class},
                (proxy, method, args) -> {
                    if ("selectForUpdate".equals(method.getName())) {
                        locksRequested.countDown();
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        MybatisAgentContextSnapshotStore target = new MybatisAgentContextSnapshotStore(
                template.getMapper(AgentContextSnapshotMapper.class), observed);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(interceptor);
        return (AgentContextSnapshotStore) proxy.getProxy();
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static AgentContextSnapshotModel snapshot(String id, long through, long version, String summary) {
        return new AgentContextSnapshotModel(id, THREAD_ID, through, version,
                AgentContextTokenEstimator.estimateText(summary), summary, NOW, 2, "legacy-v9",
                1L, AgentContextTokenEstimator.estimateText(summary), "context-summary-v2", 2_048);
    }

    private static AgentItemModel item(long sequence, String text) {
        return item(THREAD_ID, sequence, text);
    }

    private static AgentItemModel item(String threadId, long sequence, String text) {
        return new AgentItemModel(
                "item-" + threadId + "-" + sequence, threadId, "turn-" + sequence, sequence,
                AgentItemTypeEnum.USER_MESSAGE, "{\"message\":\"" + text + "\"}", NOW.plusSeconds(sequence));
    }

    private static AgentThreadModel thread(String userId, String threadId) {
        return new AgentThreadModel(threadId, userId, "context restart", AgentThreadStatusEnum.ACTIVE,
                null, null, 2L, NOW, NOW);
    }

    private static SqlSessionFactory factory(DataSource dataSource) {
        Environment environment = new Environment("context-it", new JdbcTransactionFactory(), dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentItemMapper.class);
        configuration.addMapper(AgentThreadMapper.class);
        configuration.addMapper(AgentContextSnapshotMapper.class);
        configuration.addMapper(AgentWorkflowRunMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private static SqlSessionFactory springFactory(DataSource dataSource) {
        Environment environment = new Environment("context-it-spring", new SpringManagedTransactionFactory(), dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentItemMapper.class);
        configuration.addMapper(AgentThreadMapper.class);
        configuration.addMapper(AgentContextSnapshotMapper.class);
        configuration.addMapper(AgentWorkflowRunMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private static void insertThread(DataSource dataSource) throws SQLException {
        insertThread(dataSource, USER_ID, THREAD_ID);
    }

    private static void insertThread(DataSource dataSource, String userId, String threadId) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO AGENT_THREAD "
                    + "(THREAD_ID, USER_ID, TITLE, STATUS, NEXT_SEQUENCE, CREATED_AT, UPDATED_AT) VALUES "
                    + "('" + threadId + "', '" + userId + "', 'context IT', 'ACTIVE', 1, "
                    + "'2026-09-05 00:00:00.000000', '2026-09-05 00:00:00.000000')");
        }
    }

    private static void deleteThread(DataSource dataSource, String threadId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement items = connection.prepareStatement("DELETE FROM AGENT_ITEM WHERE THREAD_ID = ?");
             PreparedStatement snapshots = connection.prepareStatement(
                     "DELETE FROM AGENT_CONTEXT_SNAPSHOT WHERE THREAD_ID = ?");
             PreparedStatement thread = connection.prepareStatement("DELETE FROM AGENT_THREAD WHERE THREAD_ID = ?")) {
            items.setString(1, threadId);
            items.executeUpdate();
            snapshots.setString(1, threadId);
            snapshots.executeUpdate();
            thread.setString(1, threadId);
            thread.executeUpdate();
        }
    }

    private static void insertLegacySnapshot(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO AGENT_CONTEXT_SNAPSHOT "
                    + "(SNAPSHOT_ID, THREAD_ID, THROUGH_SEQUENCE, VERSION_NO, ESTIMATED_TOKENS, SUMMARY, CREATED_AT) VALUES "
                    + "('legacy-v9', '" + THREAD_ID + "', 0, 1, 5, '历史摘要', '2026-09-05 00:00:00.000000')");
        }
    }

    private static void prepareV9Schema(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE AGENT_CONTEXT_SNAPSHOT DROP INDEX IDX_AGENT_CONTEXT_SNAPSHOT_BASE");
            statement.execute("ALTER TABLE AGENT_CONTEXT_SNAPSHOT DROP COLUMN SUMMARY_MAX_OUTPUT_TOKENS, "
                    + "DROP COLUMN SUMMARY_PROMPT_VERSION, DROP COLUMN SOURCE_ESTIMATED_TOKENS, "
                    + "DROP COLUMN SOURCE_FROM_SEQUENCE, DROP COLUMN BASE_SNAPSHOT_ID, DROP COLUMN FORMAT_VERSION");
            statement.execute("ALTER TABLE AGENT_WORKFLOW_RUN DROP COLUMN ORCHESTRATION_VERSION");
        }
    }

    private static void migrateToV9(DataSource dataSource, String url, String username, String password) {
        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("9")
                .target("9")
                .load()
                .migrate();
    }

    private static void migrateToV11(String url, String username, String password) {
        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static void executeBaseline(DataSource dataSource) throws IOException, SQLException {
        String source = Files.readString(findBaseline());
        StringBuilder withoutComments = new StringBuilder();
        for (String line : source.split("\\R")) {
            if (!line.stripLeading().startsWith("--")) {
                withoutComments.append(line).append('\n');
            }
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String raw : withoutComments.toString().split(";")) {
                String sql = raw.trim();
                String upper = sql.toUpperCase(Locale.ROOT);
                if (sql.isEmpty() || upper.startsWith("CREATE DATABASE") || upper.startsWith("USE ")
                        || upper.startsWith("SET FOREIGN_KEY_CHECKS")) {
                    continue;
                }
                statement.execute(sql);
            }
        }
    }

    private static Path findBaseline() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            Path baseline = candidate.resolve("docs/dev-ops/mysql/commerce-guardian-agent.sql");
            if (Files.isRegularFile(baseline)) {
                return baseline;
            }
        }
        throw new IllegalStateException("找不到 MySQL 基线脚本");
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String withoutDatabase(String url) {
        int schemeEnd = url.indexOf("://");
        int slash = url.indexOf('/', schemeEnd < 0 ? 0 : schemeEnd + 3);
        if (schemeEnd < 0 || slash < 0) {
            throw new IllegalArgumentException("MYSQL_URL 必须是 jdbc:mysql:// URL");
        }
        int query = url.indexOf('?', slash);
        return url.substring(0, slash + 1) + (query < 0 ? "" : url.substring(query));
    }

    private static String withDatabase(String url, String database) {
        int slash = url.indexOf('/', url.indexOf("://") + 3);
        int query = url.indexOf('?', slash);
        return url.substring(0, slash + 1) + database + (query < 0 ? "" : url.substring(query));
    }

    private static final class DriverManagerDataSourceBuilder {
        private DriverManagerDataSourceBuilder() {
        }

        private static DriverManagerDataSource dataSource(String url, String username, String password) {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(url);
            dataSource.setUsername(username);
            dataSource.setPassword(password);
            return dataSource;
        }

        private static Connection connection(String url, String username, String password) throws SQLException {
            return dataSource(url, username, password).getConnection();
        }
    }
}
