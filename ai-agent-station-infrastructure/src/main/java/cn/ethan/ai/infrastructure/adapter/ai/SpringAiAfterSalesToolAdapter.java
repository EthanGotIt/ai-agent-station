package cn.ethan.ai.infrastructure.adapter.ai;

import cn.ethan.ai.domain.agent.port.driven.IOrderGateway;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.policy.AfterSalesToolContractValidator;
import cn.ethan.ai.infrastructure.observability.AfterSalesRuntimeMetrics;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.NonNull;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SpringAiAfterSalesToolAdapter implements IAfterSalesToolPort {

    private static final String USER_ID_CONTEXT_KEY = "afterSalesUserId";
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile(
            "(?i)(?:订单|order)[号#:\\s-]*([A-Za-z0-9_-]{3,64})"
    );
    private static final String SYSTEM_PROMPT = """
            你是售后退款意图解析器。你只能调用 query_order，只提取用户明确提供的 orderId。
            不得猜测订单号，不得请求退款工具，不得生成用户身份字段。
            如果没有明确订单号，不调用工具。
            """;

    private final IOrderGateway orderGateway;
    private final ToolCallingManager toolCallingManager;
    private final ChatModel chatModel;
    private final String modelName;
    private final AfterSalesRuntimeMetrics metrics;

    @Autowired
    public SpringAiAfterSalesToolAdapter(IOrderGateway orderGateway,
                                         ToolCallingManager toolCallingManager,
                                         ObjectProvider<ChatModel> chatModelProvider,
                                         @Value("${after-sales.execution-model:deepseek-v4-flash}") String modelName,
                                         AfterSalesRuntimeMetrics metrics) {
        this(orderGateway, toolCallingManager, chatModelProvider.getIfAvailable(), modelName, metrics);
    }

    private SpringAiAfterSalesToolAdapter(IOrderGateway orderGateway,
                                          ToolCallingManager toolCallingManager,
                                          ChatModel chatModel,
                                          String modelName,
                                          AfterSalesRuntimeMetrics metrics) {
        this.orderGateway = orderGateway;
        this.toolCallingManager = toolCallingManager;
        this.chatModel = chatModel;
        this.modelName = modelName == null || modelName.isBlank() ? "deepseek-v4-flash" : modelName.trim();
        this.metrics = metrics;
    }

    public SpringAiAfterSalesToolAdapter(IOrderGateway orderGateway,
                                         ToolCallingManager toolCallingManager,
                                         ChatModel chatModel,
                                         String modelName) {
        this(orderGateway, toolCallingManager, chatModel, modelName, AfterSalesRuntimeMetrics.noop());
    }

    @Override
    public AfterSalesToolRequest proposeOrderQuery(String userMessage,
                                                   String userId,
                                                   String sessionId,
                                                   String orderIdHint,
                                                   String refundReason,
                                                   String correction) {
        String promptText = buildUserPrompt(userMessage, orderIdHint, refundReason, correction);
        if (chatModel != null) {
            return proposeOrderQueryWithModel(promptText);
        }
        String orderId = firstText(orderIdHint, extractOrderId(userMessage));
        if (orderId == null) {
            throw new IllegalArgumentException("ORDER_ID_REQUIRED");
        }
        return new AfterSalesToolRequest(
                UUID.randomUUID().toString(),
                AfterSalesToolContractValidator.QUERY_ORDER_TOOL,
                JSON.toJSONString(Map.of("orderId", orderId))
        );
    }

    private AfterSalesToolRequest proposeOrderQueryWithModel(String promptText) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .toolCallbacks(queryOrderCallback())
                .temperature(0.0)
                .build();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(promptText)
        ), options);
        ChatResponse response = chatModel.call(prompt);
        if (response == null) {
            throw new IllegalStateException("模型没有返回工具调用响应");
        }
        return extractSingleOrderQuery(response);
    }

    private AfterSalesToolRequest extractSingleOrderQuery(ChatResponse response) {
        if (!response.hasToolCalls() || response.getResult() == null) {
            throw new IllegalArgumentException("ORDER_ID_REQUIRED");
        }
        List<AssistantMessage.ToolCall> calls = response.getResult().getOutput().getToolCalls();
        if (calls.size() != 1) {
            throw new IllegalArgumentException("EXACTLY_ONE_ORDER_QUERY_REQUIRED");
        }
        AssistantMessage.ToolCall call = calls.get(0);
        return new AfterSalesToolRequest(call.id(), call.name(), call.arguments());
    }

    @Override
    public AfterSalesToolResult executeOrderQuery(AfterSalesToolRequest request,
                                                  String userId,
                                                  String userMessage) {
        long startedAt = System.nanoTime();
        AfterSalesToolResult result;
        try {
            ToolCallback callback = queryOrderCallback();
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(modelName)
                    .toolCallbacks(callback)
                    .toolContext(Map.of(USER_ID_CONTEXT_KEY, userId))
                    .build();
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(userMessage == null ? "查询订单" : userMessage)
            ), options);
            AssistantMessage assistantMessage = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            request.callId(), "function", request.toolName(), request.argumentsJson()
                    )))
                    .build();
            ToolExecutionResult execution = toolCallingManager.executeToolCalls(
                    prompt,
                    new ChatResponse(List.of(new Generation(assistantMessage)))
            );
            String output = extractToolOutput(execution.conversationHistory());
            result = parseToolResult(output);
        } catch (IllegalArgumentException e) {
            result = AfterSalesToolResult.failure("", "TOOL_ARGUMENT_INVALID", e.getMessage());
        } catch (Exception e) {
            result = AfterSalesToolResult.failure("", classifyException(e), e.getMessage());
        }
        metrics.recordTool(System.nanoTime() - startedAt,
                result.success() ? "success" : result.errorType());
        return result;
    }

    private ToolCallback queryOrderCallback() {
        return new ToolCallback() {
            @Override
            public @NonNull ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(AfterSalesToolContractValidator.QUERY_ORDER_TOOL)
                        .description("按用户明确提供的订单号查询订单，只读工具")
                        .inputSchema("""
                                {
                                  "type": "object",
                                  "properties": {"orderId": {"type": "string"}},
                                  "required": ["orderId"],
                                  "additionalProperties": false
                                }
                                """)
                        .build();
            }

            @Override
            public @NonNull ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(false).build();
            }

            @Override
            public @NonNull String call(@NonNull String toolInput) {
                throw new IllegalStateException("query_order requires ToolContext");
            }

            @Override
            public @NonNull String call(@NonNull String toolInput, ToolContext toolContext) {
                JSONObject input = JSON.parseObject(toolInput);
                String orderId = input == null ? null : input.getString("orderId");
                if (orderId == null || orderId.isBlank()) {
                    return error("TOOL_ARGUMENT_INVALID", "orderId 不能为空");
                }
                String userId = String.valueOf(toolContext.getContext().get(USER_ID_CONTEXT_KEY));
                return orderGateway.findOrder(orderId, userId)
                        .map(SpringAiAfterSalesToolAdapter.this::orderPayload)
                        .orElseGet(() -> error("ORDER_NOT_FOUND", "订单不存在"));
            }
        };
    }

    private String orderPayload(AfterSalesOrderSnapshot order) {
        boolean owned = !"__FOREIGN__".equals(order.ownerId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("orderId", order.orderId());
        payload.put("ownerId", order.ownerId());
        payload.put("status", order.status());
        if (owned && order.daysSinceDelivery() != null) {
            payload.put("daysSinceDelivery", order.daysSinceDelivery());
        }
        return JSON.toJSONString(payload);
    }

    private AfterSalesToolResult parseToolResult(String output) {
        JSONObject payload = JSON.parseObject(output);
        if (payload == null || !payload.getBooleanValue("success")) {
            String errorType = payload == null ? "TOOL_CALL_FAILED" : payload.getString("errorType");
            String message = payload == null ? "工具没有返回结果" : payload.getString("message");
            return AfterSalesToolResult.failure(output, errorType, message);
        }
        Integer days = payload.containsKey("daysSinceDelivery")
                ? payload.getInteger("daysSinceDelivery") : null;
        return AfterSalesToolResult.success(output, new AfterSalesOrderSnapshot(
                payload.getString("orderId"),
                payload.getString("ownerId"),
                payload.getString("status"),
                days
        ));
    }

    private String extractToolOutput(List<Message> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            Message message = history.get(index);
            if (message instanceof ToolResponseMessage responseMessage
                    && !responseMessage.getResponses().isEmpty()) {
                return responseMessage.getResponses().get(0).responseData();
            }
        }
        throw new IllegalStateException("ToolCallingManager did not return a tool response");
    }

    private String buildUserPrompt(String message, String orderIdHint, String reason, String correction) {
        return "用户消息：" + safe(message)
                + "\n订单号提示：" + safe(orderIdHint)
                + "\n退款原因提示：" + safe(reason)
                + "\n上次校验反馈：" + safe(correction);
    }

    private String extractOrderId(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = ORDER_ID_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String classifyException(Exception exception) {
        String name = exception.getClass().getSimpleName().toLowerCase();
        String message = String.valueOf(exception.getMessage()).toLowerCase();
        if (name.contains("timeout") || message.contains("timeout")) {
            return "TIMEOUT";
        }
        if (message.contains("429") || message.contains("rate limit")) {
            return "RATE_LIMITED";
        }
        return "TEMPORARY_UNAVAILABLE";
    }

    private String error(String errorType, String message) {
        return JSON.toJSONString(Map.of(
                "success", false,
                "errorType", errorType,
                "message", message
        ));
    }
}
