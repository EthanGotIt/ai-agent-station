package cn.ethan.handler;

import cn.ethan.core.agent.exception.RequestLifecycleException;
import cn.ethan.core.agent.exception.ReActExecutionException;
import cn.ethan.core.agent.exception.SessionQueueException;
import cn.ethan.core.agent.exception.AgentMemoryConflictException;
import cn.ethan.core.agent.exception.AgentMemoryNotFoundException;
import cn.ethan.dto.AgentErrorResponseDto;
import cn.ethan.core.workflow.exception.WorkflowRunConflictException;
import cn.ethan.core.workflow.exception.WorkflowRunNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Agent 异常处理器：将生命周期和参数校验异常转换为稳定的 HTTP 错误响应。
 *
 * @author ethan
 * @date 2026-08-05
 */
@RestControllerAdvice
public final class AgentExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentExceptionHandler.class);

    @ExceptionHandler(RequestLifecycleException.class)
    public ResponseEntity<AgentErrorResponseDto> lifecycle(
            RequestLifecycleException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AgentErrorResponseDto(
                        exception.getCode(),
                        exception.getMessage(),
                        exception.getRelatedRequestId()
                ));
    }

    @ExceptionHandler(SessionQueueException.class)
    public ResponseEntity<AgentErrorResponseDto> queue(SessionQueueException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new AgentErrorResponseDto(
                        exception.getCode(),
                        exception.getMessage(),
                        exception.getRelatedRequestId()
                ));
    }

    @ExceptionHandler(WorkflowRunNotFoundException.class)
    public ResponseEntity<AgentErrorResponseDto> workflowRunNotFound(
            WorkflowRunNotFoundException exception
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new AgentErrorResponseDto(
                        "WORKFLOW_RUN_NOT_FOUND",
                        "Workflow 运行不存在或不属于当前用户会话",
                        null
                ));
    }

    @ExceptionHandler(AgentMemoryNotFoundException.class)
    public ResponseEntity<AgentErrorResponseDto> memoryNotFound(AgentMemoryNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new AgentErrorResponseDto("MEMORY_NOT_FOUND", "会话记忆不存在", null));
    }

    @ExceptionHandler(AgentMemoryConflictException.class)
    public ResponseEntity<AgentErrorResponseDto> memoryConflict(AgentMemoryConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AgentErrorResponseDto("MEMORY_VERSION_CONFLICT", "会话记忆已被更新，请刷新后重试", null));
    }

    @ExceptionHandler(ReActExecutionException.class)
    public ResponseEntity<AgentErrorResponseDto> react(ReActExecutionException exception) {
        HttpStatus status = "REACT_CONFIRM_REQUIRES_STREAM".equals(exception.getCode())
                ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(new AgentErrorResponseDto(
                exception.getCode(),
                "REACT_CONFIRM_REQUIRES_STREAM".equals(exception.getCode())
                        ? "工具确认必须使用 SSE 接口" : "ReAct 执行暂时不可用",
                null
        ));
    }

    @ExceptionHandler(WorkflowRunConflictException.class)
    public ResponseEntity<AgentErrorResponseDto> workflowRunConflict(
            WorkflowRunConflictException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AgentErrorResponseDto(
                        "WORKFLOW_VERSION_CONFLICT",
                        "Workflow 检查点或版本已变化，请刷新后重试",
                        null
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AgentErrorResponseDto> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new AgentErrorResponseDto(
                        "INVALID_REQUEST",
                        exception.getMessage(),
                        null
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AgentErrorResponseDto> validation(
            MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .orElse("请求参数不合法");
        return ResponseEntity.badRequest()
                .body(new AgentErrorResponseDto(
                        "INVALID_REQUEST",
                        message,
                        null
                ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AgentErrorResponseDto> unreadable(
            HttpMessageNotReadableException exception
    ) {
        return ResponseEntity.badRequest()
                .body(new AgentErrorResponseDto(
                        "INVALID_REQUEST",
                        "请求体格式不正确",
                        null
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentErrorResponseDto> unexpected(Exception exception) {
        LOGGER.error(
                "Agent HTTP 边界发生未处理异常，exception={}",
                exception.getClass().getSimpleName()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new AgentErrorResponseDto(
                        "INTERNAL_ERROR",
                        "服务暂时不可用，请稍后重试",
                        null
                ));
    }
}
