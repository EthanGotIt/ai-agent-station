package cn.ethan.ai.config;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import javax.sql.DataSource;

/**
 * Graph Runtime checkpoint 配置。开发环境允许自动建表，生产环境应显式关闭自动 DDL。
 */
@Configuration
public class AgentGraphCheckpointConfig {

    @Bean
    @Lazy
    @ConditionalOnProperty(name = "ai-agent.graph.checkpoint.enabled", havingValue = "true", matchIfMissing = true)
    public BaseCheckpointSaver graphCheckpointSaver(
            @Qualifier("pgVectorDataSource") DataSource dataSource,
            @Value("${ai-agent.graph.checkpoint.initialize-schema:true}") boolean initializeSchema) {
        return PostgresSaver.builder()
                .datasource(dataSource)
                .createOption(initializeSchema ? CreateOption.CREATE_IF_NOT_EXISTS : CreateOption.CREATE_NONE)
                .build();
    }

}
