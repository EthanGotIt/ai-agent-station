package cn.ethan.ai.domain.agent.model;

/**
 * 只读工具的服务端可信上下文，不从模型或调用方请求体读取。
 */
public record AfterSalesToolContext(String caseId, String userId, String turnId) {
}
