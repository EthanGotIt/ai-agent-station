package cn.ethan.ai.config;

import cn.ethan.ai.domain.agent.policy.RefundInformationGatheringPolicy;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesStateMachine;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.domain.agent.port.driven.IAgentRunRepository;
import cn.ethan.ai.domain.agent.service.AfterSalesAgentService;
import cn.ethan.ai.domain.agent.service.AfterSalesAuditService;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
import cn.ethan.ai.infrastructure.adapter.statemachine.SpringStateMachineAdapter;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableScheduling
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
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(poolSize * 32),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean
    public RefundInformationGatheringPolicy refundInformationGatheringPolicy() {
        return new RefundInformationGatheringPolicy();
    }

    @Bean
    public RefundPlanningAgent refundPlanningAgent(
            @Autowired(required = false) @Qualifier("afterSalesChatClient") ChatClient chatClient) {
        return new RefundPlanningAgent(chatClient);
    }

    @Bean
    public IAfterSalesStateMachine springAfterSalesStateMachine(
            IAfterSalesToolPort toolPort,
            IAfterSalesRepository repository,
            RefundPlanningAgent refundPlanningAgent,
            RefundInformationGatheringPolicy refundInformationGatheringPolicy,
            TodoWriteTool todoWriteTool) {
        return new SpringStateMachineAdapter(toolPort, repository, refundPlanningAgent, refundInformationGatheringPolicy, todoWriteTool);
    }

    @Bean
    public AfterSalesAgentService afterSalesAgentService(IAfterSalesStateMachine stateMachine,
                                                         IAfterSalesRepository repository,
                                                         IAgentRunRepository runRepository) {
        return new AfterSalesAgentService(stateMachine, repository, new AfterSalesAuditService(runRepository));
    }
}