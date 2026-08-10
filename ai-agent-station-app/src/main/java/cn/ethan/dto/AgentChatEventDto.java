package cn.ethan.dto;

import cn.ethan.core.agent.model.OutputEventModel;

/**
 * Agent 对话事件 DTO：定义内部输出事件对外暴露时的数据结构。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record AgentChatEventDto(String type, Object data) {

    public static AgentChatEventDto from(OutputEventModel event) {
        return new AgentChatEventDto(
                event.type().name().toLowerCase(),
                event.structuredResult() != null
                        ? AgentStructuredResultDto.from(event.structuredResult())
                        : event.question() != null
                        ? AgentWorkflowQuestionEnvelopeDto.from(event.question(), event.workflowRun())
                        : event.intervention() != null
                        ? event.intervention()
                                : event.workflowRun() != null
                                        ? AgentChatWorkflowRunDto.from(event.workflowRun())
                                        : event.value()
        );
    }
}
