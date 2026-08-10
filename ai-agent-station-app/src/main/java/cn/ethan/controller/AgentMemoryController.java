package cn.ethan.controller;

import cn.ethan.core.agent.service.AgentMemoryService;
import cn.ethan.dto.AgentMemoryEditRequestDto;
import cn.ethan.dto.AgentMemoryEditResponseDto;
import cn.ethan.dto.AgentMemoryEntryDto;
import cn.ethan.dto.AgentMemoryCreateRequestDto;
import cn.ethan.dto.AgentMemoryCreateResponseDto;
import cn.ethan.dto.AgentMemoryEvidenceDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 记忆管理控制器：首期按 userId + sessionId 读取、编辑和软删除记忆。
 *
 * @author ethan
 * @date 2026-08-09
 */
@RestController
@RequestMapping("/api/v1/agent/memories")
public final class AgentMemoryController {

    private final AgentMemoryService memories;

    public AgentMemoryController(AgentMemoryService memories) {
        this.memories = memories;
    }

    @GetMapping
    public List<AgentMemoryEntryDto> list(
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return memories.list(require(userId, "X-User-Id"), require(sessionId, "sessionId"), includeDeleted, limit)
                .stream().map(AgentMemoryEntryDto::from).toList();
    }

    @PostMapping
    public ResponseEntity<AgentMemoryCreateResponseDto> create(
            @Valid @RequestBody AgentMemoryCreateRequestDto body,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        AgentMemoryEntryDto entry = AgentMemoryEntryDto.from(memories.create(
                require(userId, "X-User-Id"), require(body.sessionId(), "sessionId"), body.category(),
                body.memoryKey(), body.value(), body.expiresAt()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentMemoryCreateResponseDto.from(entry));
    }

    @PutMapping("/{entryId}")
    public ResponseEntity<AgentMemoryEditResponseDto> edit(
            @PathVariable String entryId,
            @Valid @RequestBody AgentMemoryEditRequestDto body,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        AgentMemoryEntryDto entry = AgentMemoryEntryDto.from(memories.edit(
                require(entryId, "entryId"), require(userId, "X-User-Id"),
                require(body.sessionId(), "sessionId"), body.category(), body.memoryKey(),
                body.value(), body.expiresAt(), requireVersion(body.expectedVersion())
        ));
        return ResponseEntity.ok(AgentMemoryEditResponseDto.from(entry));
    }

    @DeleteMapping("/{entryId}")
    public ResponseEntity<Void> delete(
            @PathVariable String entryId,
            @RequestParam String sessionId,
            @RequestParam long expectedVersion,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        memories.delete(require(entryId, "entryId"), require(userId, "X-User-Id"),
                require(sessionId, "sessionId"), requireVersion(expectedVersion));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{entryId}/evidence")
    public ResponseEntity<List<AgentMemoryEvidenceDto>> evidence(
            @PathVariable String entryId,
            @RequestParam String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        String currentUserId = require(userId, "X-User-Id");
        String currentSessionId = require(sessionId, "sessionId");
        String currentEntryId = require(entryId, "entryId");
        if (!memories.exists(currentEntryId, currentUserId, currentSessionId)) {
            return ResponseEntity.notFound().build();
        }
        List<AgentMemoryEvidenceDto> evidence = memories.evidence(
                        currentEntryId, currentUserId, currentSessionId)
                .stream().map(AgentMemoryEvidenceDto::from).toList();
        return ResponseEntity.ok(evidence);
    }

    private String require(String value, String name) {
        if (value == null || value.isBlank() || value.strip().length() > 128) {
            throw new IllegalArgumentException(name + " 不合法");
        }
        return value.strip();
    }

    private long requireVersion(long version) {
        if (version < 0) {
            throw new IllegalArgumentException("expectedVersion 不合法");
        }
        return version;
    }
}
