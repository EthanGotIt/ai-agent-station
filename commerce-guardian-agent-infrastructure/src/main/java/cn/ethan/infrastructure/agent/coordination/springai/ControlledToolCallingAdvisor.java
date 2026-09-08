package cn.ethan.infrastructure.agent.coordination.springai;

import cn.ethan.core.agent.execution.AgentExecutionContext;
import cn.ethan.core.agent.execution.AgentExecutionLimitException;
import cn.ethan.core.agent.execution.AgentExecutionStopReasonEnum;
import cn.ethan.core.agent.context.AgentContextPressureException;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * 类型职责：在每次模型请求前预留输出并校验完整 Prompt，在响应后一次结算 usage。
 *
 * @author ethan
 * @date 2026-09-04
 */
public final class ControlledToolCallingAdvisor extends ToolCallingAdvisor {

    static final String TOOL_STATE_KEY = "commerceGuardianAgentToolState";
    static final String OUTPUT_RESERVATION_KEY = "commerceGuardianAgentOutputReservation";

    private final int perRequestOutputTokens;
    private final int contextMaxEstimatedTokens;
    private final double compactionTriggerRatio;
    private final boolean compactionEnabled;
    private final int maxOverflowRetries;

    public ControlledToolCallingAdvisor(ToolCallingManager manager, int perRequestOutputTokens) {
        this(manager, perRequestOutputTokens, 65_536, 0.80, true, 1);
    }

    public ControlledToolCallingAdvisor(
            ToolCallingManager manager,
            int perRequestOutputTokens,
            int contextMaxEstimatedTokens,
            double compactionTriggerRatio
    ) {
        this(manager, perRequestOutputTokens, contextMaxEstimatedTokens, compactionTriggerRatio, true, 1);
    }

    public ControlledToolCallingAdvisor(
            ToolCallingManager manager,
            int perRequestOutputTokens,
            int contextMaxEstimatedTokens,
            double compactionTriggerRatio,
            boolean compactionEnabled
    ) {
        this(manager, perRequestOutputTokens, contextMaxEstimatedTokens, compactionTriggerRatio,
                compactionEnabled, 1);
    }

    public ControlledToolCallingAdvisor(
            ToolCallingManager manager,
            int perRequestOutputTokens,
            int contextMaxEstimatedTokens,
            double compactionTriggerRatio,
            boolean compactionEnabled,
            int maxOverflowRetries
    ) {
        super(manager, DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER, DEFAULT_ORDER, true);
        if (perRequestOutputTokens < 1) {
            throw new IllegalArgumentException("perRequestOutputTokens must be positive");
        }
        this.perRequestOutputTokens = perRequestOutputTokens;
        this.contextMaxEstimatedTokens = Math.max(1_000, contextMaxEstimatedTokens);
        this.compactionTriggerRatio = compactionTriggerRatio;
        this.compactionEnabled = compactionEnabled;
        this.maxOverflowRetries = Math.max(0, Math.min(maxOverflowRetries, 3));
    }

    @Override
    protected ChatClientRequest doBeforeStream(
            ChatClientRequest request,
            StreamAdvisorChain advisorChain
    ) {
        AgentToolExecutionState state = state(request);
        if (state == null) {
            return request;
        }
        AgentExecutionContext context = state.executionContext();
        if (context == null) {
            return request;
        }
        context.checkActive();
        AgentPromptMeasurement.Measurement measurement = AgentPromptMeasurement.measure(request.prompt());
        int estimate = measurement.estimatedTokens();
        context.recordPromptMeasurement(context.contextViewKey(), estimate);
        if (context.contextOverflowRecoveryPending()
                && !context.validateContextOverflowRecovery(context.contextViewKey(), estimate, maxOverflowRetries)) {
            state.markResourceStop(AgentExecutionStopReasonEnum.CONTEXT_BUDGET_EXCEEDED);
            throw new AgentExecutionLimitException(AgentExecutionStopReasonEnum.CONTEXT_BUDGET_EXCEEDED);
        }
        int pressureThreshold = Math.max(1, (int) Math.floor(contextMaxEstimatedTokens * compactionTriggerRatio));
        if (compactionEnabled && estimate >= pressureThreshold
                && !context.contextCompactionAttempted(context.contextViewKey())) {
            throw new AgentContextPressureException("模型上下文达到压缩压力阈值",
                    estimate, context.contextViewKey());
        }
        if (!context.checkContextBudget(estimate)) {
            AgentExecutionStopReasonEnum reason = context.stopReason();
            state.markResourceStop(reason);
            throw new AgentExecutionLimitException(reason);
        }
        String reservationId = context.reserveOutput(perRequestOutputTokens);
        if (reservationId == null) {
            AgentExecutionStopReasonEnum reason = context.stopReason();
            state.markResourceStop(reason);
            throw new AgentExecutionLimitException(reason);
        }
        int reserved = context.reservedOutputTokens(reservationId);
        state.bindModelOutputReservation(reservationId);
        ChatOptions options = request.prompt().getOptions();
        ChatOptions boundedOptions = options == null
                ? ToolCallingChatOptions.builder().maxTokens(reserved).build()
                : options.mutate().maxTokens(reserved).build();
        Prompt boundedPrompt = request.prompt().mutate().chatOptions(boundedOptions).build();
        return request.mutate().prompt(boundedPrompt).context(OUTPUT_RESERVATION_KEY, reservationId).build();
    }

    @Override
    protected ChatClientResponse doAfterStream(
            ChatClientResponse response,
            StreamAdvisorChain advisorChain
    ) {
        AgentToolExecutionState state = response == null ? null : state(response);
        if (state != null && response.chatResponse() != null) {
            Object reservation = response.context() == null ? null
                    : response.context().get(OUTPUT_RESERVATION_KEY);
            state.settleModelOutput(response.chatResponse(), reservation instanceof String value ? value : null);
        }
        return response;
    }

    private AgentToolExecutionState state(Prompt prompt) {
        if (prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
                && options.getToolContext() != null) {
            Object value = options.getToolContext().get(TOOL_STATE_KEY);
            return value instanceof AgentToolExecutionState state ? state : null;
        }
        return null;
    }

    private AgentToolExecutionState state(ChatClientRequest request) {
        if (request.context() != null) {
            Object value = request.context().get(TOOL_STATE_KEY);
            if (value instanceof AgentToolExecutionState state) {
                return state;
            }
        }
        return state(request.prompt());
    }

    private AgentToolExecutionState state(ChatClientResponse response) {
        if (response.context() == null) {
            return null;
        }
        Object value = response.context().get(TOOL_STATE_KEY);
        return value instanceof AgentToolExecutionState state ? state : null;
    }

}
