package cn.ethan.ai.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Objects;

@Configuration
public class DataSourceConfig {

    @Bean("mysqlDataSource")
    @Primary
    public DataSource mysqlDataSource(
            @Value("${spring.datasource.mysql.driver-class-name}") String driverClassName,
            @Value("${spring.datasource.mysql.url}") String url,
            @Value("${spring.datasource.mysql.username}") String username,
            @Value("${spring.datasource.mysql.password}") String password,
            @Value("${spring.datasource.mysql.hikari.maximum-pool-size}") int maximumPoolSize,
            @Value("${spring.datasource.mysql.hikari.minimum-idle}") int minimumIdle,
            @Value("${spring.datasource.mysql.hikari.idle-timeout}") long idleTimeout,
            @Value("${spring.datasource.mysql.hikari.connection-timeout}") long connectionTimeout,
            @Value("${spring.datasource.mysql.hikari.max-lifetime}") long maxLifetime) {
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

    @Bean
    public SqlSessionFactory sqlSessionFactory(
            @Qualifier("mysqlDataSource") DataSource mysqlDataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(mysqlDataSource);
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        factoryBean.setMapperLocations(resolver.getResources("classpath:/mybatis/mapper/*.xml"));
        return Objects.requireNonNull(factoryBean.getObject());
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    @Bean("mysqlTransactionManager")
    @Primary
    public PlatformTransactionManager mysqlTransactionManager(
            @Qualifier("mysqlDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean("mysqlTransactionTemplate")
    public TransactionTemplate mysqlTransactionTemplate(
            @Qualifier("mysqlTransactionManager") PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

}
