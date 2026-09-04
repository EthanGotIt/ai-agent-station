package cn.ethan.infrastructure.agent.coordination.springai;

import cn.ethan.core.agent.execution.AgentExecutionContext;
import cn.ethan.core.agent.execution.AgentExecutionLimitException;
import cn.ethan.core.agent.execution.AgentExecutionStopReasonEnum;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * 类型职责：在每次模型请求前预留输出并校验完整 Prompt，在响应后一次结算 usage。
 *
 * @author ethan
 * @date 2026-09-04
 */
public final class ControlledToolCallingAdvisor extends ToolCallingAdvisor {

    static final String TOOL_STATE_KEY = "commerceGuardianAgentToolState";

    private final int perRequestOutputTokens;

    public ControlledToolCallingAdvisor(ToolCallingManager manager, int perRequestOutputTokens) {
        super(manager, DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER, DEFAULT_ORDER, true);
        if (perRequestOutputTokens < 1) {
            throw new IllegalArgumentException("perRequestOutputTokens must be positive");
        }
        this.perRequestOutputTokens = perRequestOutputTokens;
    }

    @Override
    protected ChatClientRequest doBeforeStream(
            ChatClientRequest request,
            StreamAdvisorChain advisorChain
    ) {
        AgentToolExecutionState state = state(request.prompt());
        if (state == null) {
            return request;
        }
        AgentExecutionContext context = state.executionContext();
        if (context == null) {
            return request;
        }
        context.checkActive();
        int estimate = estimate(request.prompt());
        if (!context.checkContextBudget(estimate)) {
            AgentExecutionStopReasonEnum reason = context.stopReason();
            state.markResourceStop(reason);
            throw new AgentExecutionLimitException(reason);
        }
        if (context.reserveOutput(perRequestOutputTokens) == null) {
            AgentExecutionStopReasonEnum reason = context.stopReason();
            state.markResourceStop(reason);
            throw new AgentExecutionLimitException(reason);
        }
        return request;
    }

    @Override
    protected ChatClientResponse doAfterStream(
            ChatClientResponse response,
            StreamAdvisorChain advisorChain
    ) {
        AgentToolExecutionState state = response == null ? null : state(response);
        if (state != null && response.chatResponse() != null) {
            state.settleModelOutput(response.chatResponse());
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

    private AgentToolExecutionState state(ChatClientResponse response) {
        Object value = response.context().get(TOOL_STATE_KEY);
        return value instanceof AgentToolExecutionState state ? state : null;
    }

    private int estimate(Prompt prompt) {
        long characters = 0L;
        for (Message message : prompt.getInstructions()) {
            if (message != null && message.getText() != null) {
                characters += message.getText().length();
            }
            if (message instanceof AssistantMessage assistant) {
                for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                    characters += toolCall.id().length() + toolCall.name().length()
                            + (toolCall.arguments() == null ? 0 : toolCall.arguments().length());
                }
            }
            if (message instanceof ToolResponseMessage toolResponse) {
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    characters += response.id().length() + response.name().length()
                            + (response.responseData() == null ? 0 : response.responseData().length());
                }
            }
        }
        if (prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
                && options.getToolCallbacks() != null) {
            for (var callback : options.getToolCallbacks()) {
                var definition = callback.getToolDefinition();
                characters += definition.name().length()
                        + definition.description().length()
                        + definition.inputSchema().length();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, characters / 2L + 1L);
    }
}
