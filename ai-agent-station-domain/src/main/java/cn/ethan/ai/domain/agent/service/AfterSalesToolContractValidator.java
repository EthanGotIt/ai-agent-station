package cn.ethan.ai.domain.agent.service;

import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.util.Set;
import java.util.regex.Pattern;

public final class AfterSalesToolContractValidator {

    public static final String QUERY_ORDER_TOOL = "query_order";
    private static final Set<String> ALLOWED_FIELDS = Set.of("orderId");
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{3,64}");

    public ValidationResult validate(AfterSalesToolRequest request, String orderIdHint) {
        if (request == null || !QUERY_ORDER_TOOL.equals(request.toolName())) {
            return ValidationResult.invalid("TOOL_NOT_ALLOWED", "只允许调用 query_order");
        }
        try {
            JSONObject arguments = JSON.parseObject(request.argumentsJson());
            if (arguments == null || arguments.keySet().stream().anyMatch(key -> !ALLOWED_FIELDS.contains(key))) {
                return ValidationResult.invalid("TOOL_ARGUMENT_INVALID", "工具参数只允许 orderId");
            }
            String orderId = arguments.getString("orderId");
            if (orderId == null || !ORDER_ID_PATTERN.matcher(orderId).matches()) {
                return ValidationResult.invalid("TOOL_ARGUMENT_INVALID", "orderId 格式非法");
            }
            if (orderIdHint != null && !orderIdHint.isBlank() && !orderIdHint.equals(orderId)) {
                return ValidationResult.invalid("TOOL_ARGUMENT_CONFLICT", "模型生成的 orderId 与用户提供值不一致");
            }
            return ValidationResult.valid(orderId);
        } catch (Exception e) {
            return ValidationResult.invalid("TOOL_ARGUMENT_INVALID", "工具参数不是合法 JSON");
        }
    }

    public record ValidationResult(boolean valid, String orderId, String errorType, String message) {
        public static ValidationResult valid(String orderId) {
            return new ValidationResult(true, orderId, null, null);
        }

        public static ValidationResult invalid(String errorType, String message) {
            return new ValidationResult(false, null, errorType, message);
        }
    }
}
