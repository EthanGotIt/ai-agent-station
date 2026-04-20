package cn.ethan.ai.config;

import com.zaxxer.hikari.HikariDataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Objects;

@Configuration
public class DataSourceConfig {

    @Bean("mysqlDataSource")
    @Primary
    public DataSource mysqlDataSource(@Value("${spring.datasource.mysql.driver-class-name}") String driverClassName,
                                        @Value("${spring.datasource.mysql.url}") String url,
                                        @Value("${spring.datasource.mysql.username}") String username,
                                        @Value("${spring.datasource.mysql.password}") String password,
                                        @Value("${spring.datasource.mysql.hikari.maximum-pool-size}") int maximumPoolSize,
                                        @Value("${spring.datasource.mysql.hikari.minimum-idle}") int minimumIdle,
                                        @Value("${spring.datasource.mysql.hikari.idle-timeout}") long idleTimeout,
                                        @Value("${spring.datasource.mysql.hikari.connection-timeout}") long connectionTimeout,
                                        @Value("${spring.datasource.mysql.hikari.max-lifetime}") long maxLifetime) {
        // 连接池配置
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName(driverClassName);
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);

        dataSource.setMaximumPoolSize(maximumPoolSize);
        dataSource.setMinimumIdle(minimumIdle);
        dataSource.setIdleTimeout(idleTimeout);
        dataSource.setConnectionTimeout(connectionTimeout);
        dataSource.setMaxLifetime(maxLifetime);
        dataSource.setPoolName("MySQLHikariPool");

        return dataSource;
    }

    @Bean("sqlSessionFactoryBean")
    public SqlSessionFactoryBean sqlSessionFactoryBean(@Qualifier("mysqlDataSource") DataSource mysqlDataSource) throws Exception {
        SqlSessionFactoryBean sqlSessionFactoryBean = new SqlSessionFactoryBean();
        sqlSessionFactoryBean.setDataSource(mysqlDataSource);

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        sqlSessionFactoryBean.setConfigLocation(resolver.getResource("classpath:/mybatis/config/mybatis-config.xml"));
        sqlSessionFactoryBean.setMapperLocations(resolver.getResources("classpath:/mybatis/mapper/*.xml"));

        return sqlSessionFactoryBean;
    }

    @Bean("sqlSessionTemplate")
    public SqlSessionTemplate sqlSessionTemplate(@Qualifier("sqlSessionFactoryBean") SqlSessionFactoryBean sqlSessionFactoryBean) throws Exception {
        return new SqlSessionTemplate(Objects.requireNonNull(sqlSessionFactoryBean.getObject()));
    }

    @Bean("pgVectorDataSource")
    @ConditionalOnProperty(name = "ai-agent.vector-store.enabled", havingValue = "true")
    public DataSource pgVectorDataSource(@Value("${spring.datasource.pgvector.driver-class-name}") String driverClassName,
                                         @Value("${spring.datasource.pgvector.url}") String url,
                                         @Value("${spring.datasource.pgvector.username}") String username,
                                         @Value("${spring.datasource.pgvector.password}") String password,
                                         @Value("${spring.datasource.pgvector.hikari.maximum-pool-size}") int maximumPoolSize,
                                         @Value("${spring.datasource.pgvector.hikari.minimum-idle}") int minimumIdle,
                                         @Value("${spring.datasource.pgvector.hikari.idle-timeout}") long idleTimeout,
                                         @Value("${spring.datasource.pgvector.hikari.connection-timeout}") long connectionTimeout) {
        // 连接池配置
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName(driverClassName);
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);

        dataSource.setMaximumPoolSize(maximumPoolSize);
        dataSource.setMinimumIdle(minimumIdle);
        dataSource.setIdleTimeout(idleTimeout);
        dataSource.setConnectionTimeout(connectionTimeout);

        // 确保在启动时连接数据库
        dataSource.setInitializationFailTimeout(1);
        dataSource.setConnectionTestQuery("SELECT 1");
        dataSource.setAutoCommit(true);
        dataSource.setPoolName("PgVectorHikariPool");
        return dataSource;
    }

    @Bean("pgVectorJdbcTemplate")
    @ConditionalOnProperty(name = "ai-agent.vector-store.enabled", havingValue = "true")
    public JdbcTemplate pgVectorJdbcTemplate(@Qualifier("pgVectorDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

}
