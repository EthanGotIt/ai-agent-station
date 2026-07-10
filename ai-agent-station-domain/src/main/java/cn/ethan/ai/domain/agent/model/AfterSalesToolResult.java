package cn.ethan.ai.domain.agent.model;

public record AfterSalesToolResult(boolean success,
                                   String outputJson,
                                   ToolEvidence evidence,
                                   String errorType,
                                   String errorMessage) {

    public static AfterSalesToolResult success(String outputJson, ToolEvidence evidence) {
        return new AfterSalesToolResult(true, outputJson, evidence, null, null);
    }

    public static AfterSalesToolResult failure(String outputJson, String errorType, String errorMessage) {
        return new AfterSalesToolResult(false, outputJson, null, errorType, errorMessage);
    }

}
