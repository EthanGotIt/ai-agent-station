package cn.ethan.ai.domain.agent.model;

public record AfterSalesToolResult(boolean success,
                                   String outputJson,
                                   AfterSalesOrderSnapshot order,
                                   String errorType,
                                   String errorMessage) {

    public static AfterSalesToolResult success(String outputJson, AfterSalesOrderSnapshot order) {
        return new AfterSalesToolResult(true, outputJson, order, null, null);
    }

    public static AfterSalesToolResult failure(String outputJson, String errorType, String errorMessage) {
        return new AfterSalesToolResult(false, outputJson, null, errorType, errorMessage);
    }
}
