package cn.ethan.config;

import cn.ethan.core.agent.port.ReActExecutor;
import cn.ethan.core.agent.port.OutputObservationProvider;
import cn.ethan.core.agent.service.AgentMemoryService;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import cn.ethan.core.order.port.LogisticsGateway;
import cn.ethan.core.order.port.OrderGateway;
import cn.ethan.infrastructure.agentscope.executor.AgentScopeReActExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.time.Clock;

/**
 * AgentScope 装配配置：将基础设施 ReAct 执行器接入 Core 端口。
 *
 * @author ethan
 * @date 2026-08-06
 */
@Configuration
@EnableConfigurationProperties(AgentScopeReActProperties.class)
public class AgentScopeConfiguration {

    @Bean(destroyMethod = "close")
    public ReActExecutor agentScopeReActExecutor(
            AgentScopeReActProperties properties,
            OrderGateway orderGateway,
            LogisticsGateway logisticsGateway,
            AfterSalesCaseGateway afterSalesCaseGateway,
            RefundCommandGateway refundCommandGateway,
            AgentMemoryService agentMemoryService,
            OutputObservationProvider observationProvider,
            Clock clock,
            Environment environment,
            @Value("${ai-agent.model.react:qwen3.7-plus}") String modelName
            , @Value("${ai-agent.agentscope.react.acceptance-confirmation-probe-enabled:false}")
            boolean acceptanceConfirmationProbeEnabled
    ) {
        boolean enableAcceptanceProbe = acceptanceConfirmationProbeEnabled
                && environment.acceptsProfiles(Profiles.of("acceptance"));
        return AgentScopeReActExecutor.createWithClasspathSkillRepository(
                properties.apiKey(),
                properties.baseUrl(),
                modelName,
                properties.timeout(),
                properties.maxIterations(),
                properties.maxOutputTokens(),
                properties.maxRetries(),
                properties.thinkingEnabled(),
                properties.thinkingBudget(),
                orderGateway,
                logisticsGateway,
                afterSalesCaseGateway,
                refundCommandGateway,
                agentMemoryService,
                enableAcceptanceProbe,
                observationProvider,
                clock
        );
    }
}
