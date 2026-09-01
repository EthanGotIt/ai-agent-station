package cn.ethan.app.agent.api;

import cn.ethan.core.agent.execution.AgentExecutionTimelineService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 类型职责：提供只读 Turn 执行轨迹回放，回放不会再次调用模型或外部系统。
 *
 * @author ethan
 * @date 2026-08-20
 */
@RestController
@RequestMapping("/api/agent")
public final class AgentTurnExecutionController {

    private final AgentExecutionTimelineService executions;
    private final AgentUserContext userContext;

    public AgentTurnExecutionController(AgentExecutionTimelineService executions, AgentUserContext userContext) {
        this.executions = executions;
        this.userContext = userContext;
    }

    @GetMapping("/turns/{turnId}/execution")
    public AgentTurnExecutionResponseDto get(@PathVariable String turnId, HttpServletRequest request) {
        return AgentTurnExecutionResponseDto.from(
                executions.get(userContext.currentUserId(request), turnId));
    }
}
