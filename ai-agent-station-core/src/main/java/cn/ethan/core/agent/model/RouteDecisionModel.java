package cn.ethan.core.agent.model;

import cn.ethan.core.agent.enums.RouteTypeEnum;

import java.util.List;
import java.util.Map;

/**
 * 路由决策模型：承载轻量意图路由器生成的结构化结果。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record RouteDecisionModel(
        RouteTypeEnum routeType,
        String domainId,
        String executorId,
        String operation,
        String normalizedIntent,
        Map<String, String> parameters,
        List<String> requiredFields,
        String reasonCode
) {

    public RouteDecisionModel {
        routeType = routeType == null ? RouteTypeEnum.CLARIFY : routeType;
        domainId = domainId == null ? "" : domainId.trim();
        executorId = executorId == null ? "" : executorId;
        operation = operation == null ? "" : operation.trim();
        normalizedIntent = normalizedIntent == null ? "" : normalizedIntent;
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
        reasonCode = reasonCode == null ? "UNKNOWN" : reasonCode;
    }

    /**
     * 保持旧路由提供方在一次兼容周期内可用；新增字段由受控规则或归一化逻辑补齐。
     */
    public RouteDecisionModel(
            RouteTypeEnum routeType,
            String executorId,
            String normalizedIntent,
            List<String> requiredFields,
            String reasonCode
    ) {
        this(routeType, "", executorId, "", normalizedIntent, Map.of(), requiredFields, reasonCode);
    }

    public static RouteDecisionModel clarify(String reasonCode, List<String> requiredFields) {
        return new RouteDecisionModel(
                RouteTypeEnum.CLARIFY, "", "", "", "", Map.of(), requiredFields, reasonCode
        );
    }
}
