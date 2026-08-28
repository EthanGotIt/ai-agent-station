package cn.ethan.app.bootstrap;

import cn.ethan.app.agent.stream.InMemoryAgentEventBus;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentThreadStore;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.context.AgentContextAssembler;
import cn.ethan.core.agent.context.AgentContextSnapshotStore;
import cn.ethan.core.agent.execution.AgentTurnRuntimeService;
import cn.ethan.core.agent.execution.AgentExecutionTimelineService;
import cn.ethan.core.agent.execution.AgentRuntimeMetrics;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.action.ExternalActionService;
import cn.ethan.core.agent.thread.AgentThreadService;
import cn.ethan.core.agent.coordination.AgentTurnCoordinator;
import cn.ethan.core.agent.coordination.AgentOrderActionCoordinator;
import cn.ethan.core.agent.workflow.AgentQuestionCardStore;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStore;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import io.netty.channel.ChannelOption;

import java.time.Clock;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 类型职责：装配 Thread Runtime 的边界端口和可配置执行资源。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Configuration
@EnableScheduling
@MapperScan({
        "cn.ethan.infrastructure.commerce.order.persistence",
        "cn.ethan.infrastructure.agent.thread.persistence",
        "cn.ethan.infrastructure.agent.action.persistence",
        "cn.ethan.infrastructure.agent.workflow.persistence"
})
@EnableConfigurationProperties({
        AgentRuntimeProperties.class,
        AgentThreadProperties.class,
        AgentModelProperties.class
})
public class AgentConfiguration {

    @Bean
    public Clock agentClock() {
        return Clock.systemUTC();
    }

    @Bean(name = "agentChatClient")
    public ChatClient agentChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    public RestClient.Builder deepSeekRestClientBuilder(AgentModelProperties properties) {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(properties.httpTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(properties.httpTimeout());
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Bean
    public WebClient.Builder deepSeekWebClientBuilder(AgentModelProperties properties) {
        int timeoutMillis = Math.toIntExact(properties.httpTimeout().toMillis());
        HttpClient client = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis)
                .responseTimeout(properties.httpTimeout());
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(client));
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
    public InMemoryAgentEventBus agentThreadEventPublisher() {
        return new InMemoryAgentEventBus();
    }

    @Bean
    public AgentThreadService agentThreadService(
            AgentThreadStore threads,
            AgentItemStore items,
            Clock clock
    ) {
        return new AgentThreadService(threads, items, clock);
    }

    @Bean
    public AgentRuntimeMetrics agentRuntimeMetrics(MeterRegistry registry) {
        return new MicrometerAgentRuntimeMetrics(registry);
    }

    @Bean
    public AgentExecutionTimelineService agentExecutionTimelineService(
            AgentTurnStore turns,
            AgentThreadService threads
    ) {
        return new AgentExecutionTimelineService(turns, threads);
    }

    @Bean
    public ExternalActionService externalActionService(ExternalActionCommandStore commands, Clock clock) {
        return new ExternalActionService(commands, clock);
    }

    @Bean
    public AgentContextAssembler agentContextAssembler(
            AgentItemStore items,
            AgentContextSnapshotStore snapshots,
            Clock clock,
            AgentThreadProperties properties
    ) {
        return new AgentContextAssembler(items, snapshots, clock,
                properties.contextMaxEstimatedTokens(), properties.snapshotTriggerEstimatedTokens(),
                properties.toolResultMaxCharacters(), properties.outputReserveEstimatedTokens());
    }

    @Bean
    public AgentTurnRuntimeService agentTurnRuntimeService(
            AgentThreadStore threadStore,
            AgentTurnStore turns,
            AgentItemStore items,
            AgentThreadService threads,
            AgentContextAssembler contextAssembler,
            AgentTurnCoordinator coordinator,
            AgentOrderActionCoordinator orderActionCoordinator,
            AgentThreadEventGateway events,
            ThreadPoolTaskExecutor agentTaskExecutor,
            ScheduledExecutorService agentQueueTimeoutScheduler,
            Clock clock,
            AgentRuntimeProperties runtimeProperties,
            AgentThreadProperties threadProperties,
            AgentRuntimeMetrics metrics,
            AgentQuestionCardStore questionCards,
            AgentWorkflowCheckpointStore checkpoints
    ) {
        AgentTurnRuntimeService runtime = new AgentTurnRuntimeService(
                threadStore, turns, items, threads, contextAssembler, coordinator,
                events, agentTaskExecutor, agentQueueTimeoutScheduler, clock,
                runtimeProperties.queue().maxPendingPerThread(),
                runtimeProperties.queue().maxPendingGlobal(),
                runtimeProperties.queue().waitTimeout(),
                threadProperties.turnTimeout(),
                threadProperties.toolResultMaxCharacters(), metrics, orderActionCoordinator,
                runtimeProperties.continuationEnabled(), runtimeProperties.maxAgentCycles(), questionCards, checkpoints);
        runtime.recoverPersistedTurns();
        return runtime;
    }
}
