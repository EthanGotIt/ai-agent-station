package cn.ethan.config;

import cn.ethan.core.agent.thread.port.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.port.AgentThreadStore;
import cn.ethan.core.agent.thread.service.AgentContextAssembler;
import cn.ethan.core.agent.thread.service.AgentThreadRuntimeService;
import cn.ethan.core.agent.thread.service.AgentThreadService;
import cn.ethan.core.agent.thread.port.AgentCoordinatorProvider;
import cn.ethan.core.agent.thread.support.InMemoryAgentThreadEventGateway;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 类型职责：装配 v3 Thread Runtime 的边界端口和可配置执行资源。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Configuration
@EnableScheduling
@MapperScan({
        "cn.ethan.infrastructure.order.mapper",
        "cn.ethan.infrastructure.agent.thread.mapper",
        "cn.ethan.infrastructure.agent.action.mapper"
})
@EnableConfigurationProperties({AgentRuntimeProperties.class, AgentThreadProperties.class})
public class AgentConfiguration {

    @Bean
    public Clock agentClock() {
        return Clock.systemUTC();
    }

    @Bean
    public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    }

    @Bean(name = "routerChatClient")
    public ChatClient routerChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService agentQueueTimeoutScheduler() {
        AtomicInteger threadNumber = new AtomicInteger();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "agent-queue-timeout-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor agentTaskExecutor(AgentRuntimeProperties properties) {
        AgentRuntimeProperties.ExecutorProperties values = properties.executor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-thread-");
        executor.setCorePoolSize(values.corePoolSize());
        executor.setMaxPoolSize(values.maxPoolSize());
        executor.setQueueCapacity(values.queueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(values.awaitTerminationSeconds());
        executor.initialize();
        return executor;
    }

    @Bean
    public InMemoryAgentThreadEventGateway agentThreadEventPublisher() {
        return new InMemoryAgentThreadEventGateway();
    }

    @Bean
    public AgentThreadService agentThreadService(AgentThreadStore store, Clock clock) {
        return new AgentThreadService(store, clock);
    }

    @Bean
    public AgentContextAssembler agentContextAssembler(
            AgentThreadStore store,
            Clock clock,
            AgentThreadProperties properties
    ) {
        return new AgentContextAssembler(store, clock,
                properties.contextMaxEstimatedTokens(), properties.snapshotTriggerEstimatedTokens(),
                properties.toolResultMaxCharacters(), properties.outputReserveEstimatedTokens());
    }

    @Bean
    public AgentThreadRuntimeService agentThreadRuntimeService(
            AgentThreadStore store,
            AgentThreadService threads,
            AgentContextAssembler contextAssembler,
            AgentCoordinatorProvider coordinator,
            AgentThreadEventGateway events,
            ThreadPoolTaskExecutor agentTaskExecutor,
            ScheduledExecutorService agentQueueTimeoutScheduler,
            Clock clock,
            AgentRuntimeProperties runtimeProperties,
            AgentThreadProperties threadProperties
    ) {
        AgentThreadRuntimeService runtime = new AgentThreadRuntimeService(
                store, threads, contextAssembler, coordinator, events, agentTaskExecutor,
                agentQueueTimeoutScheduler, clock,
                runtimeProperties.queue().maxPendingPerThread(),
                runtimeProperties.queue().maxPendingGlobal(),
                runtimeProperties.queue().waitTimeout(),
                threadProperties.turnTimeout(),
                threadProperties.toolResultMaxCharacters());
        runtime.recoverPersistedTurns();
        return runtime;
    }
}
