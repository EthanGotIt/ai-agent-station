package cn.ethan.controller;

import cn.ethan.core.agent.enums.AgentMemoryCategoryEnum;
import cn.ethan.core.agent.enums.AgentMemoryOriginEnum;
import cn.ethan.core.agent.model.AgentMemoryEntryModel;
import cn.ethan.core.agent.service.AgentMemoryService;
import cn.ethan.core.agent.exception.AgentMemoryConflictException;
import cn.ethan.handler.AgentExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话记忆 HTTP 测试：验证默认隐藏删除项和跨会话访问不可见。
 *
 * @author ethan
 * @date 2026-08-10
 */
class AgentMemoryControllerTest {

    private AgentMemoryService memories;
    private MockMvc mockMvc;

    @BeforeEach
    void createController() {
        memories = mock(AgentMemoryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentMemoryController(memories))
                .setControllerAdvice(new AgentExceptionHandler())
                .build();
    }

    @Test
    void listHidesDeletedEntriesUnlessExplicitlyRequested() throws Exception {
        when(memories.list("user-1", "session-1", false, 50)).thenReturn(List.of(entry()));

        mockMvc.perform(get("/api/v1/agent/memories")
                        .header("X-User-Id", "user-1")
                        .param("sessionId", "session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entryId").value("memory-1"))
                .andExpect(jsonPath("$[0].deleted").value(false));

        verify(memories).list("user-1", "session-1", false, 50);
    }

    @Test
    void evidenceForAnotherSessionAppearsNotFound() throws Exception {
        when(memories.exists("memory-1", "user-1", "session-2")).thenReturn(false);

        mockMvc.perform(get("/api/v1/agent/memories/memory-1/evidence")
                        .header("X-User-Id", "user-1")
                        .param("sessionId", "session-2"))
                .andExpect(status().isNotFound());

        verify(memories).exists("memory-1", "user-1", "session-2");
    }

    @Test
    void editWithStaleVersionAppearsAsConflict() throws Exception {
        when(memories.edit("memory-1", "user-1", "session-1", AgentMemoryCategoryEnum.PREFERENCE,
                "response.language", "en-US", null, 0L))
                .thenThrow(new AgentMemoryConflictException("memory-1"));

        mockMvc.perform(put("/api/v1/agent/memories/memory-1")
                        .header("X-User-Id", "user-1")
                        .contentType("application/json")
                        .content("""
                                {"sessionId":"session-1","category":"PREFERENCE","memoryKey":"response.language",
                                "value":"en-US","expectedVersion":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMORY_VERSION_CONFLICT"));
    }

    private AgentMemoryEntryModel entry() {
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        return new AgentMemoryEntryModel(
                "memory-1", null, "user-1", "session-1", AgentMemoryCategoryEnum.PREFERENCE,
                "response.language", "中文", AgentMemoryOriginEnum.MANUAL, 1.0,
                0L, false, null, now, now
        );
    }
}
