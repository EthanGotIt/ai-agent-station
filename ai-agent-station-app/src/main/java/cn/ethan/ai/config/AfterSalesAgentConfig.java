package cn.ethan.ai.config;

import cn.ethan.ai.domain.agent.service.AfterSalesAgentService;
import cn.ethan.ai.domain.agent.service.AfterSalesGraphRuntime;
import cn.ethan.ai.domain.agent.adapter.repository.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.adapter.port.IAfterSalesToolPort;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRunRepository;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.CreateOption;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.checkpoint.MysqlSaver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class AfterSalesAgentConfig {

    @Bean(name = "agentIoExecutor", destroyMethod = "shutdown")
    public ExecutorService agentIoExecutor() {
        int poolSize = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "after-sales-io-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(poolSize, threadFactory);
    }

    @Bean("afterSalesCheckpointSaver")
    @ConditionalOnProperty(name = "ai-agent.after-sales.checkpoint-store", havingValue = "mysql")
    public BaseCheckpointSaver mysqlAfterSalesCheckpointSaver(
            @Qualifier("mysqlDataSource") DataSource dataSource) {
        return MysqlSaver.builder()
                .dataSource(dataSource)
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .build();
    }

    @Bean("afterSalesCheckpointSaver")
    @ConditionalOnProperty(name = "ai-agent.after-sales.checkpoint-store",
            havingValue = "memory", matchIfMissing = true)
    public BaseCheckpointSaver memoryAfterSalesCheckpointSaver() {
        return new MemorySaver();
    }

    @Bean
    public AfterSalesGraphRuntime afterSalesGraphRuntime(
            @Qualifier("afterSalesCheckpointSaver") BaseCheckpointSaver checkpointSaver,
            IAfterSalesToolPort toolPort,
            IAfterSalesRepository repository,
            @Qualifier("agentIoExecutor") ExecutorService ioExecutor) throws GraphStateException {
        return new AfterSalesGraphRuntime(checkpointSaver, toolPort, repository, ioExecutor);
    }

    @Bean
    public AfterSalesAgentService afterSalesAgentService(AfterSalesGraphRuntime graphRuntime,
                                                         IAfterSalesRepository repository,
                                                         IAgentRunRepository runRepository) {
        return new AfterSalesAgentService(graphRuntime, repository, runRepository);
    }
}
