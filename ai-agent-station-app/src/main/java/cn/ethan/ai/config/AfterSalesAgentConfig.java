package cn.ethan.ai.config;

import cn.ethan.ai.domain.agent.policy.RefundInformationGatheringPolicy;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesStateMachine;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesBoundaryRepository;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.domain.agent.port.driven.IAgentTurnRepository;
import cn.ethan.ai.domain.agent.port.driven.ICheckpointRepository;
import cn.ethan.ai.domain.agent.service.AfterSalesAgentService;
import cn.ethan.ai.domain.agent.service.AfterSalesAuditService;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
import cn.ethan.ai.infrastructure.adapter.statemachine.SpringStateMachineAdapter;
import cn.ethan.ai.infrastructure.observability.AfterSalesRuntimeMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class AfterSalesAgentConfig {

    @Bean
    public AfterSalesRuntimeMetrics afterSalesRuntimeMetrics(MeterRegistry meterRegistry) {
        return new AfterSalesRuntimeMetrics(meterRegistry);
    }

    @Bean
    public RefundInformationGatheringPolicy refundInformationGatheringPolicy() {
        return new RefundInformationGatheringPolicy();
    }

    @Bean
    public RefundPlanningAgent refundPlanningAgent(
            @Autowired(required = false) @Qualifier("afterSalesPlanningChatClient") ChatClient chatClient,
            AfterSalesRuntimeMetrics metrics) {
        return new RefundPlanningAgent(chatClient, metrics);
    }

    @Bean
    public IAfterSalesStateMachine springAfterSalesStateMachine(
            IAfterSalesToolPort toolPort,
            IAfterSalesRepository repository,
            RefundPlanningAgent refundPlanningAgent,
            RefundInformationGatheringPolicy refundInformationGatheringPolicy,
            TodoWriteTool todoWriteTool,
            ICheckpointRepository checkpointRepository,
            AfterSalesRuntimeMetrics metrics) {
        return new SpringStateMachineAdapter(toolPort, repository, refundPlanningAgent,
                refundInformationGatheringPolicy, todoWriteTool, checkpointRepository, metrics);
    }

    @Bean
    public AfterSalesAgentService afterSalesAgentService(IAfterSalesStateMachine stateMachine,
                                                         IAfterSalesRepository repository,
                                                         IAgentTurnRepository turnRepository,
                                                         IAfterSalesBoundaryRepository boundaryRepository) {
        return new AfterSalesAgentService(stateMachine, repository,
                new AfterSalesAuditService(turnRepository), boundaryRepository);
    }
}
