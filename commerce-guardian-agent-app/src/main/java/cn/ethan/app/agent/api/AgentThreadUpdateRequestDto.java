package cn.ethan.app.agent.api;

import cn.ethan.core.agent.thread.AgentThreadModel;
import jakarta.validation.constraints.Size;

/**
 * 类型职责：接收 Thread 标题更新；历史归档状态不再提供写入口。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentThreadUpdateRequestDto(
        @Size(max = AgentThreadModel.MAX_TITLE_LENGTH) String title
) {
}
