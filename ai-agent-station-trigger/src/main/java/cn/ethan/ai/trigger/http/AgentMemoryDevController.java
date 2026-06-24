package cn.ethan.ai.trigger.http;

import cn.ethan.ai.domain.agent.service.execute.runtime.AgentConversationMemoryService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 仅本地开发环境使用的 Session 记忆清理入口。
 */
@Profile("dev")
@RestController
@RequestMapping("/api/v1/agent/session")
public class AgentMemoryDevController {

    private final AgentConversationMemoryService memoryService;

    public AgentMemoryDevController(AgentConversationMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @DeleteMapping("/{sessionId}/memory")
    public Map<String, Object> clear(@PathVariable String sessionId) {
        memoryService.clearSessionMemory(sessionId);
        return Map.of("sessionId", sessionId, "cleared", true);
    }
}
