package cn.ethan.infrastructure.agent.coordination.springai;

import cn.ethan.core.agent.execution.AgentExecutionCancelledException;
import cn.ethan.core.agent.execution.AgentExecutionContext;
import cn.ethan.core.agent.execution.AgentExecutionStopReasonEnum;
import cn.ethan.core.agent.execution.AgentExecutionTimeoutException;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 类型职责：按模型返回顺序执行工具，并在终止事实或资源停止后立即截断同批调用。
 *
 * @author ethan
 * @date 2026-09-04
 */
public final class ControlledToolCallingManager implements ToolCallingManager {

    private static final String TOOL_FAILURE_MESSAGE = "工具调用失败，请根据失败结果调整当前决策。";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        if (chatOptions == null || chatOptions.getToolCallbacks() == null) {
            return List.of();
        }
        return chatOptions.getToolCallbacks().stream()
                .map(ToolCallback::getToolDefinition)
                .toList();
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        Optional<Generation> generation = chatResponse.getResults().stream()
                .filter(value -> value.getOutput() != null && value.getOutput().hasToolCalls())
                .findFirst();
        if (generation.isEmpty()) {
            throw new IllegalStateException("No tool call requested by the chat model");
        }
        AssistantMessage assistant = generation.get().getOutput();
        Map<String, Object> contextValues = new HashMap<>();
        List<ToolCallback> callbacks = List.of();
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
            if (options.getToolContext() != null) {
                contextValues.putAll(options.getToolContext());
            }
            if (options.getToolCallbacks() != null) {
                callbacks = options.getToolCallbacks();
            }
        }
        ToolContext toolContext = new ToolContext(contextValues);
        AgentToolExecutionState state = state(contextValues);
        AgentExecutionContext executionContext = state == null ? null : state.executionContext();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        boolean returnDirect = false;
        boolean allowFirstToolAfterOutputBudget = executionContext != null
                && executionContext.stopReason() == AgentExecutionStopReasonEnum.OUTPUT_BUDGET_EXCEEDED;

        int toolIndex = 0;
        for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
            if (state != null && (state.terminal() || state.persistenceFailed())) {
                returnDirect = true;
                break;
            }
            if (executionContext != null) {
                executionContext.checkActive();
                if (executionContext.stopped()
                        && !(allowFirstToolAfterOutputBudget && toolIndex == 0)) {
                    AgentExecutionStopReasonEnum reason = executionContext.stopReason();
                    state.markResourceStop(reason);
                    responses.add(response(toolCall, safeStop(reason)));
                    returnDirect = true;
                    break;
                }
            }

            String toolName = toolCall.name();
            String arguments = toolCall.arguments() == null || toolCall.arguments().isBlank()
                    ? "{}" : toolCall.arguments();
            ToolCallback callback = callbacks.stream()
                    .filter(value -> toolName.equals(value.getToolDefinition().name()))
                    .findFirst()
                    .orElse(null);
            if (callback == null) {
                String errorCode = "TOOL_NOT_FOUND";
                boolean repeated = executionContext != null
                        && executionContext.recordToolFailure(toolName, canonical(arguments), errorCode);
                if (repeated) {
                    state.markRepeatedToolFailure();
                    returnDirect = true;
                }
                responses.add(response(toolCall, failure(errorCode)));
                if (returnDirect) {
                    break;
                }
                continue;
            }

            try {
                String value = callback.call(arguments, toolContext);
                if (executionContext != null) {
                    executionContext.recordToolSuccess();
                }
                responses.add(response(toolCall, state == null
                        ? SpringAiOrderToolSupport.boundToolValue(value)
                        : state.boundToolResult(value)));
                returnDirect = returnDirect || callback.getToolMetadata().returnDirect();
            }
            catch (RuntimeException failure) {
                if (failure instanceof AgentExecutionCancelledException
                        || failure instanceof AgentExecutionTimeoutException) {
                    throw failure;
                }
                String errorCode = stableErrorCode(failure);
                boolean repeated = executionContext != null
                        && executionContext.recordToolFailure(toolName, canonical(arguments), errorCode);
                responses.add(response(toolCall, failure(errorCode)));
                if (repeated) {
                    state.markRepeatedToolFailure();
                    returnDirect = true;
                    break;
                }
                if (state != null && state.persistenceFailed()) {
                    returnDirect = true;
                    break;
                }
            }
            toolIndex++;
        }

        if (allowFirstToolAfterOutputBudget && executionContext != null
                && executionContext.stopReason() == AgentExecutionStopReasonEnum.OUTPUT_BUDGET_EXCEEDED
                && state != null && !state.terminal()) {
            state.markResourceStop(AgentExecutionStopReasonEnum.OUTPUT_BUDGET_EXCEEDED);
            returnDirect = true;
        }

        ToolResponseMessage responseMessage = ToolResponseMessage.builder().responses(responses).build();
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(assistant);
        history.add(responseMessage);
        return ToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(returnDirect || (state != null
                        && (state.terminal() || state.persistenceFailed())))
                .build();
    }

    private AgentToolExecutionState state(Map<String, Object> context) {
        Object value = context.get(ControlledToolCallingAdvisor.TOOL_STATE_KEY);
        return value instanceof AgentToolExecutionState state ? state : null;
    }

    private ToolResponseMessage.ToolResponse response(
            AssistantMessage.ToolCall call,
            String value
    ) {
        return new ToolResponseMessage.ToolResponse(call.id(), call.name(), value == null ? "" : value);
    }

    private String safeStop(AgentExecutionStopReasonEnum reason) {
        return failure(reason == null ? "TOOL_EXECUTION_STOPPED" : reason.name());
    }

    private String failure(String errorCode) {
        return "{\"status\":\"FAILED\",\"errorCode\":\""
                + escape(errorCode) + "\",\"message\":\"" + escape(TOOL_FAILURE_MESSAGE) + "\"}";
    }

    private String stableErrorCode(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getClass().getName().contains("ToolExecution")) {
            current = current.getCause();
        }
        if (current instanceof AgentThreadConflictException conflict) {
            return conflict.code();
        }
        return switch (current.getClass().getSimpleName()) {
            case "IllegalArgumentException" -> "TOOL_INVALID_ARGUMENT";
            case "IllegalStateException" -> "TOOL_INVALID_STATE";
            default -> "TOOL_CALL_FAILED";
        };
    }

    private String canonical(String arguments) {
        try {
            JsonNode root = objectMapper.readTree(arguments);
            return canonicalNode(root);
        }
        catch (RuntimeException parseFailure) {
            // 非 JSON 参数仍需保持稳定签名；调用边界会返回受控参数错误。
            return arguments == null ? "" : arguments.replaceAll("\\s+", "");
        }
    }

    private String canonicalNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            StringBuilder value = new StringBuilder("{");
            boolean first = true;
            List<Map.Entry<String, JsonNode>> entries = node.properties().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .toList();
            for (Map.Entry<String, JsonNode> entry : entries) {
                if (!first) {
                    value.append(',');
                }
                value.append('"').append(escape(entry.getKey())).append("\":")
                        .append(canonicalNode(entry.getValue()));
                first = false;
            }
            return value.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder value = new StringBuilder("[");
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) {
                    value.append(',');
                }
                value.append(canonicalNode(node.get(index)));
            }
            return value.append(']').toString();
        }
        return node.toString();
    }

    private String escape(String value) {
        return SpringAiOrderToolSupport.escapeJson(value);
    }
}
