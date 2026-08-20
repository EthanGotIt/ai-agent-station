package cn.ethan.app.agent.api;

import cn.ethan.core.agent.thread.AgentThreadModel;
import jakarta.validation.constraints.Size;

/**
 * 类型职责：接收 Thread 创建所需的标题和可选业务上下文。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentThreadCreateRequestDto(
        @Size(max = AgentThreadModel.MAX_TITLE_LENGTH) String title,
        @Size(max = AgentThreadModel.MAX_CONTEXT_TYPE_LENGTH) String contextType,
        @Size(max = AgentThreadModel.MAX_CONTEXT_ID_LENGTH) String contextId
) {
}
