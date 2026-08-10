package cn.ethan.core.agent.model;

import java.util.List;

/**
 * 工具人工确认卡：在原 ReAct SSE 连接上发送，并由旁路接口提交决定。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record ToolInterventionModel(String replyId, String message, List<ToolInterventionToolModel> tools) {

    public ToolInterventionModel {
        if (replyId == null || replyId.isBlank() || tools == null || tools.isEmpty()) {
            throw new IllegalArgumentException("tool intervention is incomplete");
        }
        message = message == null ? "请确认工具调用" : message.strip();
        tools = List.copyOf(tools);
    }
}
