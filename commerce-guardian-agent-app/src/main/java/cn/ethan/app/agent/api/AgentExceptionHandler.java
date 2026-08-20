package cn.ethan.app.agent.api;

import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentThreadNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;

/**
 * 类型职责：将 Agent API 的归属、并发、校验和未知错误转换为稳定响应。
 *
 * @author ethan
 * @date 2026-08-19
 */
@RestControllerAdvice
public final class AgentExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentExceptionHandler.class);

    @ExceptionHandler(AgentThreadConflictException.class)
    public ResponseEntity<AgentErrorResponseDto> conflict(AgentThreadConflictException exception) {
        HttpStatus status = exception.code().contains("QUEUE")
                ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.CONFLICT;
        return ResponseEntity.status(status)
                .body(new AgentErrorResponseDto(exception.code(), exception.getMessage(), null));
    }

    @ExceptionHandler(AgentThreadNotFoundException.class)
    public ResponseEntity<AgentErrorResponseDto> notFound(AgentThreadNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new AgentErrorResponseDto("THREAD_NOT_FOUND", exception.getMessage(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AgentErrorResponseDto> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new AgentErrorResponseDto("INVALID_REQUEST", exception.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AgentErrorResponseDto> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("请求参数不合法");
        return ResponseEntity.badRequest()
                .body(new AgentErrorResponseDto("INVALID_REQUEST", message, null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AgentErrorResponseDto> unreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(new AgentErrorResponseDto("INVALID_REQUEST", "请求体格式不正确", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentErrorResponseDto> unexpected(Exception exception) {
        LOGGER.error("Agent HTTP 边界发生未处理异常，exception={}", exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new AgentErrorResponseDto("INTERNAL_ERROR", "服务暂时不可用，请稍后重试", null));
    }
}
