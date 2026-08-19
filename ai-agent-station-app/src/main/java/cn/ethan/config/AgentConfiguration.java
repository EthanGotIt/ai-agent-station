package cn.ethan.config;

import cn.ethan.core.agent.port.OutputObservationProvider;
import cn.ethan.core.agent.port.ReActExecutor;
import cn.ethan.core.agent.port.RouteDecisionProvider;
import cn.ethan.core.agent.port.ConversationStore;
import cn.ethan.core.agent.port.AgentMemoryStore;
import cn.ethan.core.agent.port.AgentMemoryExtractionProvider;
import cn.ethan.core.agent.support.AgentMemoryExtractionCoordinator;
import cn.ethan.core.agent.service.AgentRouterService;
import cn.ethan.core.agent.service.AgentRuntimeService;
import cn.ethan.core.agent.service.AgentMemoryService;
import cn.ethan.core.agent.service.OutputManager;
import cn.ethan.core.agent.service.RequestLifecycleManager;
import cn.ethan.core.agent.service.SessionExecutionQueueManager;
import cn.ethan.core.agent.thread.port.AgentCoordinatorProvider;
import cn.ethan.core.agent.thread.port.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.port.AgentThreadStore;
import cn.ethan.core.agent.thread.service.AgentContextAssembler;
import cn.ethan.core.agent.thread.service.AgentThreadService;
import cn.ethan.core.agent.thread.service.AgentThreadRuntimeService;
import cn.ethan.core.agent.thread.support.InMemoryAgentThreadEventGateway;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.core.after_sales.port.AfterSalesRefundSubmissionGateway;
import cn.ethan.core.after_sales.port.RefundExecutor;
import cn.ethan.core.after_sales.service.AfterSalesRequestAnalysisService;
import cn.ethan.core.after_sales.service.AfterSalesReviewService;
import cn.ethan.core.after_sales.service.RefundCommandLifecycleService;
import cn.ethan.core.after_sales.service.RefundEligibilityService;
import cn.ethan.core.order.port.OrderGateway;
import cn.ethan.core.order.port.LogisticsGateway;
import cn.ethan.core.order.service.OrderRequestAnalysisService;
import cn.ethan.core.workflow.engine.GraphExecutor;
import cn.ethan.core.workflow.order.OrderInquiryWorkflow;
import cn.ethan.core.workflow.after_sales.AfterSalesRefundWorkflow;
import cn.ethan.core.workflow.port.WorkflowRunEventStore;
import cn.ethan.core.workflow.port.WorkflowRunStore;
import cn.ethan.core.workflow.service.WorkflowRegistryService;
import cn.ethan.infrastructure.qwen.provider.QwenRouteDecisionProvider;
import cn.ethan.infrastructure.qwen.provider.QwenMemoryExtractionProvider;
import cn.ethan.infrastructure.qwen.provider.RouterPolicyPromptProvider;
import cn.ethan.infrastructure.memory.mapper.AgentMemoryEntryMapper;
import cn.ethan.infrastructure.memory.mapper.AgentMemoryEvidenceMapper;
import cn.ethan.infrastructure.memory.mapper.AgentMemorySourceMapper;
import cn.ethan.infrastructure.memory.store.MybatisAgentMemoryStore;
import cn.ethan.infrastructure.after_sales.manager.RefundCommandSettlementManager;
import cn.ethan.infrastructure.after_sales.worker.RefundCommandWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 装配配置：集中注册应用 Bean，业务执行逻辑保留在 core 模块。
 *
 * @author ethan
 * @date 2026-08-05
 */
@Configuration
@MapperScan({
        "cn.ethan.infrastructure.order.mapper",
        "cn.ethan.infrastructure.after_sales.mapper",
        "cn.ethan.infrastructure.memory.mapper",
        "cn.ethan.infrastructure.agent.thread.mapper"
})
@EnableConfigurationProperties({
        AgentRuntimeProperties.class,
        AgentThreadProperties.class,
        AgentRouterProperties.class,
        OrderDiagnosisProperties.class,
        AgentMemoryProperties.class,
        RefundWorkerProperties.class
})
public class AgentConfiguration {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    public ChatClient routerChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean
    public RouteDecisionProvider qwenRouteDecisionProvider(
            ChatClient routerChatClient,
            AgentRouterProperties properties,
            RouterPolicyPromptProvider routerPolicyPrompt
    ) {
        return new QwenRouteDecisionProvider(
                routerChatClient,
                properties.thinkingEnabled(),
                properties.thinkingBudget(),
                routerPolicyPrompt.content()
        );
    }

    @Bean
    public RouterPolicyPromptProvider routerPolicyPromptProvider() {
        return RouterPolicyPromptProvider.fromClasspath();
    }

    @Bean
    public GraphExecutor graphExecutor() {
        return new GraphExecutor();
    }

    @Bean
    public OutputManager outputManager(OutputObservationProvider observationProvider,
                                       Clock clock) {
        return new OutputManager(observationProvider, clock);
    }

    @Bean
    public Clock agentClock() {
        return Clock.systemUTC();
    }

    @Bean
    public AgentMemoryStore agentMemoryStore(
            AgentMemorySourceMapper sourceMapper,
            AgentMemoryEntryMapper entryMapper,
            AgentMemoryEvidenceMapper evidenceMapper
    ) {
        return new MybatisAgentMemoryStore(sourceMapper, entryMapper, evidenceMapper);
    }

    @Bean
    public AgentMemoryService agentMemoryService(
            AgentMemoryProperties properties,
            AgentMemoryStore store,
            Clock clock,
            OutputObservationProvider observationProvider
    ) {
        return new AgentMemoryService(
                properties.generationEnabled(), properties.usageEnabled(),
                properties.minimumAutoConfidence(), store, clock, observationProvider
        );
    }

    @Bean
    public AgentMemoryExtractionProvider qwenMemoryExtractionProvider(ChatClient routerChatClient) {
        return new QwenMemoryExtractionProvider(routerChatClient);
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService agentMemoryScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "agent-memory-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor agentMemoryExecutor(AgentMemoryProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-memory-extraction-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(properties.extractionQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(destroyMethod = "close")
    public AgentMemoryExtractionCoordinator agentMemoryExtractionCoordinator(
            AgentMemoryProperties properties,
            @Qualifier("agentMemoryScheduler") ScheduledExecutorService agentMemoryScheduler,
            @Qualifier("agentMemoryExecutor") ThreadPoolTaskExecutor agentMemoryExecutor,
            AgentMemoryExtractionProvider qwenMemoryExtractionProvider,
            AgentMemoryService agentMemoryService
    ) {
        return new AgentMemoryExtractionCoordinator(
                properties.idleDelay(), agentMemoryScheduler, agentMemoryExecutor,
                qwenMemoryExtractionProvider, agentMemoryService
        );
    }

    @Bean
    public RequestLifecycleManager requestLifecycleManager(
            AgentRuntimeProperties properties,
            Clock clock
    ) {
        return new RequestLifecycleManager(properties.requestTerminalTtl(), clock);
    }

    @Bean
    public ThreadPoolTaskExecutor agentTaskExecutor(AgentRuntimeProperties properties) {
        AgentRuntimeProperties.ExecutorProperties executorProperties = properties.executor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-execution-");
        executor.setCorePoolSize(executorProperties.corePoolSize());
        executor.setMaxPoolSize(executorProperties.maxPoolSize());
        executor.setQueueCapacity(executorProperties.queueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(executorProperties.awaitTerminationSeconds());
        return executor;
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService agentQueueTimeoutScheduler() {
        AtomicInteger threadNumber = new AtomicInteger();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
                1,
                task -> {
                    Thread thread = new Thread(
                            task,
                            "agent-queue-timeout-" + threadNumber.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                }
        );
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Bean
    public SessionExecutionQueueManager sessionExecutionQueueManager(
            ThreadPoolTaskExecutor agentTaskExecutor,
            ScheduledExecutorService agentQueueTimeoutScheduler,
            AgentRuntimeProperties properties
    ) {
        AgentRuntimeProperties.QueueProperties queueProperties = properties.queue();
        return new SessionExecutionQueueManager(
                queueProperties.maxPendingPerSession(),
                queueProperties.maxPendingGlobal(),
                queueProperties.waitTimeout(),
                agentTaskExecutor,
                agentQueueTimeoutScheduler
        );
    }

    @Bean
    public AgentRouterService agentRouterService(
            RouteDecisionProvider decisionProvider,
            WorkflowRegistryService workflows,
            OrderRequestAnalysisService orderRequestAnalysis,
            AfterSalesRequestAnalysisService afterSalesRequestAnalysis
    ) {
        return new AgentRouterService(
                decisionProvider,
                workflows,
                orderRequestAnalysis,
                afterSalesRequestAnalysis
        );
    }

    @Bean
    public OrderRequestAnalysisService orderRequestAnalysisService() {
        return new OrderRequestAnalysisService();
    }

    @Bean
    public AfterSalesRequestAnalysisService afterSalesRequestAnalysisService(
            OrderRequestAnalysisService orderRequestAnalysis
    ) {
        return new AfterSalesRequestAnalysisService(orderRequestAnalysis);
    }

    @Bean
    public RefundEligibilityService refundEligibilityService() {
        return new RefundEligibilityService();
    }

    @Bean
    public AfterSalesReviewService afterSalesReviewService(
            AfterSalesCaseGateway afterSalesCaseGateway,
            RefundCommandGateway refundCommandGateway,
            Clock clock
    ) {
        return new AfterSalesReviewService(afterSalesCaseGateway, refundCommandGateway, clock);
    }

    @Bean
    public RefundCommandLifecycleService refundCommandLifecycleService(
            AfterSalesCaseGateway afterSalesCaseGateway,
            RefundCommandGateway refundCommandGateway,
            Clock clock,
            RefundWorkerProperties properties
    ) {
        return new RefundCommandLifecycleService(
                afterSalesCaseGateway, refundCommandGateway, clock,
                properties.maxAttempts(), properties.retryDelay(), properties.leaseDuration()
        );
    }

    @Bean
    public RefundCommandWorker refundCommandWorker(
            RefundCommandLifecycleService refundCommandLifecycleService,
            RefundExecutor refundExecutor,
            RefundCommandSettlementManager refundCommandSettlementManager,
            RefundWorkerProperties properties
    ) {
        return new RefundCommandWorker(
                refundCommandLifecycleService, refundExecutor, refundCommandSettlementManager,
                properties.batchSize()
        );
    }

    @Bean(name = "taskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("refund-command-worker-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    public OrderInquiryWorkflow orderInquiryWorkflow(
            OrderGateway gateway,
            LogisticsGateway logisticsGateway,
            GraphExecutor executor,
            OrderRequestAnalysisService orderRequestAnalysis,
            Clock clock,
            OrderDiagnosisProperties properties,
            WorkflowRunStore workflowRunStore,
            WorkflowRunEventStore workflowRunEventStore
    ) {
        return new OrderInquiryWorkflow(
                gateway,
                logisticsGateway,
                executor,
                orderRequestAnalysis,
                clock,
                properties.shipmentDelayThreshold(),
                properties.logisticsStallThreshold(),
                workflowRunStore,
                workflowRunEventStore
        );
    }

    @Bean
    public AfterSalesRefundWorkflow afterSalesRefundWorkflow(
            OrderGateway orderGateway,
            RefundCommandGateway refundCommandGateway,
            AfterSalesCaseGateway afterSalesCaseGateway,
            AfterSalesRefundSubmissionGateway afterSalesRefundSubmissionGateway,
            WorkflowRunStore workflowRunStore,
            WorkflowRunEventStore workflowRunEventStore,
            AfterSalesRequestAnalysisService afterSalesRequestAnalysis,
            RefundEligibilityService refundEligibilityService,
            GraphExecutor executor,
            Clock clock
    ) {
        return new AfterSalesRefundWorkflow(
                orderGateway,
                refundCommandGateway,
                afterSalesCaseGateway,
                afterSalesRefundSubmissionGateway,
                workflowRunStore,
                workflowRunEventStore,
                afterSalesRequestAnalysis,
                refundEligibilityService,
                executor,
                clock
        );
    }

    @Bean
    public WorkflowRegistryService workflowRegistryService(
            OrderInquiryWorkflow orderInquiryWorkflow,
            AfterSalesRefundWorkflow afterSalesRefundWorkflow
    ) {
        return new WorkflowRegistryService(List.of(orderInquiryWorkflow, afterSalesRefundWorkflow));
    }

    @Bean
    public AgentRuntimeService agentRuntimeService(
            RequestLifecycleManager lifecycle,
            SessionExecutionQueueManager queueManager,
            AgentRouterService router,
            ReActExecutor react,
            WorkflowRegistryService workflows,
            OutputManager outputManager,
            Clock clock,
            ConversationStore conversations,
            AgentRouterProperties routerProperties,
            WorkflowRunStore workflowRunStore,
            AgentMemoryService agentMemoryService,
            AgentMemoryExtractionCoordinator agentMemoryExtractionCoordinator
    ) {
        return new AgentRuntimeService(
                lifecycle,
                queueManager,
                router,
                react,
                workflows,
                outputManager,
                clock,
                conversations,
                routerProperties.historyTurns() * 2,
                routerProperties.historyMaxCharacters(),
                workflowRunStore,
                agentMemoryService,
                agentMemoryExtractionCoordinator
        );
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
        return new AgentContextAssembler(
                store, clock,
                properties.contextMaxEstimatedTokens(),
                properties.snapshotTriggerEstimatedTokens(),
                properties.toolResultMaxCharacters(),
                properties.outputReserveEstimatedTokens()
        );
    }

    @Bean
    public InMemoryAgentThreadEventGateway agentThreadEventPublisher() {
        return new InMemoryAgentThreadEventGateway();
    }

    @Bean
    public AgentThreadRuntimeService agentThreadRuntimeService(
            AgentThreadStore store,
            AgentThreadService threads,
            AgentContextAssembler contextAssembler,
            AgentCoordinatorProvider coordinator,
            AgentThreadEventGateway events,
            @Qualifier("agentTaskExecutor") ThreadPoolTaskExecutor executor,
            @Qualifier("agentQueueTimeoutScheduler") ScheduledExecutorService scheduler,
            Clock clock,
            AgentRuntimeProperties properties,
            AgentThreadProperties threadProperties
    ) {
        AgentThreadRuntimeService runtime = new AgentThreadRuntimeService(
                store,
                threads,
                contextAssembler,
                coordinator,
                events,
                executor,
                scheduler,
                clock,
                properties.queue().maxPendingPerSession(),
                properties.queue().maxPendingGlobal(),
                properties.queue().waitTimeout(),
                threadProperties.turnTimeout(),
                threadProperties.toolResultMaxCharacters()
        );
        runtime.recoverPersistedTurns();
        return runtime;
    }
}
