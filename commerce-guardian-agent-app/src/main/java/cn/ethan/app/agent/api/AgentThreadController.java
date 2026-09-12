package cn.ethan.app.agent.api;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentThreadService;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardStore;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 类型职责：提供 Thread 元数据和游标化 Item 历史的 HTTP 协议转换。
 *
 * @author ethan
 * @date 2026-08-20
 */
@RestController
@RequestMapping("/api/agent")
public final class AgentThreadController {

    private final AgentThreadService threads;
    private final AgentUserContext userContext;
    private final AgentQuestionCardStore questions;
    private final AgentWorkflowCheckpointStore checkpoints;
    private final ObjectMapper objectMapper;

    /** 供 Spring 使用的完整 HTTP payload 转换构造器。 */
    @Autowired
    public AgentThreadController(
            AgentThreadService threads,
            AgentUserContext userContext,
            AgentQuestionCardStore questions,
            AgentWorkflowCheckpointStore checkpoints,
            ObjectMapper objectMapper
    ) {
        this.threads = threads;
        this.userContext = userContext;
        this.questions = questions;
        this.checkpoints = checkpoints;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /** 保留无 JSON 依赖的测试/嵌入式装配入口。 */
    public AgentThreadController(
            AgentThreadService threads,
            AgentUserContext userContext,
            AgentQuestionCardStore questions,
            AgentWorkflowCheckpointStore checkpoints
    ) {
        this(threads, userContext, questions, checkpoints, new ObjectMapper());
    }

    @PostMapping("/threads")
    public AgentThreadDto create(
            @Valid @RequestBody(required = false) AgentThreadCreateRequestDto body,
            HttpServletRequest request
    ) {
        AgentThreadCreateRequestDto input = body == null
                ? new AgentThreadCreateRequestDto(null, null, null)
                : body;
        return AgentThreadDto.from(threads.create(userContext.currentUserId(request),
                input.title(), input.contextType(), input.contextId()));
    }

    @GetMapping("/threads")
    public AgentThreadPageResponseDto list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ACTIVE") String status,
            HttpServletRequest request
    ) {
        AgentThreadStatusEnum lifecycle = AgentThreadStatusEnum.valueOf(status.strip().toUpperCase(Locale.ROOT));
        var result = threads.listPage(userContext.currentUserId(request), lifecycle, page, size);
        return new AgentThreadPageResponseDto(result.items().stream().map(AgentThreadDto::from).toList(),
                result.page(), result.size(), result.total());
    }

    @GetMapping("/threads/{threadId}")
    public AgentThreadDto get(@PathVariable String threadId, HttpServletRequest request) {
        return AgentThreadDto.from(threads.get(userContext.currentUserId(request), threadId));
    }

    @GetMapping("/threads/{threadId}/interaction")
    public ResponseEntity<AgentThreadInteractionDto> interaction(
            @PathVariable String threadId,
            HttpServletRequest request
    ) {
        String userId = userContext.currentUserId(request);
        threads.get(userId, threadId);
        if (questions != null) {
            var question = questions.findOpen(userId, threadId);
            if (question.isPresent()) {
                return ResponseEntity.ok(AgentThreadInteractionDto.from(question.get()));
            }
        }
        if (checkpoints != null) {
            var checkpoint = checkpoints.findOpen(userId, threadId);
            if (checkpoint.isPresent()) {
                return ResponseEntity.ok(AgentThreadInteractionDto.from(checkpoint.get()));
            }
        }
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/threads/{threadId}")
    public AgentThreadDto update(
            @PathVariable String threadId,
            @Valid @RequestBody AgentThreadUpdateRequestDto body,
            HttpServletRequest request
    ) {
        return AgentThreadDto.from(threads.update(userContext.currentUserId(request), threadId,
                body.title()));
    }

    @GetMapping("/threads/{threadId}/items")
    public AgentItemPageResponseDto items(
            @PathVariable String threadId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "200") int limit,
            HttpServletRequest request
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        long safeAfterSequence = Math.max(0, afterSequence);
        var loaded = threads.listItems(userContext.currentUserId(request), threadId,
                safeAfterSequence, safeLimit + 1);
        var items = loaded == null ? java.util.List.<AgentItemModel>of() : loaded;
        var sorted = items.stream()
                .filter(item -> item != null && item.sequence() > safeAfterSequence)
                .sorted(Comparator.comparingLong(AgentItemModel::sequence))
                .toList();
        List<AgentItemModel> ordered = new ArrayList<>(sorted.size());
        long previousSequence = safeAfterSequence;
        boolean invalidPage = false;
        for (AgentItemModel item : sorted) {
            if (item.sequence() <= previousSequence) {
                invalidPage = true;
                continue;
            }
            ordered.add(item);
            previousSequence = item.sequence();
        }
        var page = ordered.stream().limit(safeLimit).toList();
        long next = page.stream().mapToLong(AgentItemModel::sequence)
                .max().orElse(safeAfterSequence);
        // 重复或非严格前进的持久化页只能安全收口，不能让客户端依据失真的 hasMore 无限追读。
        boolean hasMore = !invalidPage && ordered.size() > safeLimit && next > safeAfterSequence;
        return new AgentItemPageResponseDto(page.stream().map(item -> AgentItemDto.from(item, objectMapper)).toList(),
                safeAfterSequence, next, hasMore);
    }
}
