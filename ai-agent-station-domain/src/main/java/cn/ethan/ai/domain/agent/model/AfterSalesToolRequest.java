package cn.ethan.ai.domain.agent.model;

public record AfterSalesToolRequest(String callId, String toolName, String argumentsJson) {
}
