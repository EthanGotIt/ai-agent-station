package cn.ethan.infrastructure.agent.coordination.springai;

import cn.ethan.core.agent.context.AgentContextTokenEstimator;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 类型职责：用同一规则测量 Spring AI 实际发送的完整模型 Prompt。
 *
 * @author ethan
 * @date 2026-09-05
 */
final class AgentPromptMeasurement {

    private AgentPromptMeasurement() {
    }

    static Measurement measure(Prompt prompt) {
        long characters = 0L;
        if (prompt != null) {
            for (Message message : prompt.getInstructions()) {
                if (message != null && message.getText() != null) {
                    characters = saturatingAdd(characters, message.getText().length());
                }
                if (message instanceof AssistantMessage assistant) {
                    for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                        characters = saturatingAdd(characters, length(toolCall.id()));
                        characters = saturatingAdd(characters, length(toolCall.name()));
                        characters = saturatingAdd(characters, length(toolCall.arguments()));
                    }
                }
                if (message instanceof ToolResponseMessage toolResponse) {
                    for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                        characters = saturatingAdd(characters, length(response.id()));
                        characters = saturatingAdd(characters, length(response.name()));
                        characters = saturatingAdd(characters, length(response.responseData()));
                    }
                }
            }
            if (prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
                    && options.getToolCallbacks() != null) {
                for (var callback : options.getToolCallbacks()) {
                    var definition = callback.getToolDefinition();
                    characters = saturatingAdd(characters, length(definition.name()));
                    characters = saturatingAdd(characters, length(definition.description()));
                    characters = saturatingAdd(characters, length(definition.inputSchema()));
                }
            }
        }
        return new Measurement(AgentContextTokenEstimator.estimateCharacters(characters), characters);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L) {
            return left;
        }
        return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    record Measurement(int estimatedTokens, long characters) {
    }
}
