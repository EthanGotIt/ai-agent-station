package cn.ethan.ai.trigger.http;

import cn.ethan.ai.api.dto.AfterSalesResumeRequestDTO;
import cn.ethan.ai.api.dto.AfterSalesRunRequestDTO;
import cn.ethan.ai.api.dto.AfterSalesRunResponseDTO;
import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesResumeCommand;
import cn.ethan.ai.domain.agent.model.AfterSalesRunCommand;
import cn.ethan.ai.domain.agent.model.AfterSalesRunResult;
import cn.ethan.ai.domain.agent.service.AfterSalesAgentService;
import cn.ethan.ai.domain.agent.exception.AfterSalesResumeConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/after-sales/cases")
public class AfterSalesAgentController {

    private final AfterSalesAgentService afterSalesAgentService;

    public AfterSalesAgentController(AfterSalesAgentService afterSalesAgentService) {
        this.afterSalesAgentService = afterSalesAgentService;
    }

    @PostMapping
    public AfterSalesRunResponseDTO start(@RequestHeader("X-User-Id") String userId,
                                          @RequestBody AfterSalesRunRequestDTO request) {
        if (request.userId() != null && !request.userId().isBlank()
                && !userId.equals(request.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请求身份与消息身份不一致");
        }
        return toResponse(afterSalesAgentService.start(AfterSalesRunCommand.of(
                userId,
                request.sessionId(),
                request.message(),
                request.orderId(),
                request.refundReason()
        )));
    }

    @PostMapping("/{caseId}/resume")
    public AfterSalesRunResponseDTO resume(@PathVariable String caseId,
                                           @RequestHeader("X-User-Id") String actorId,
                                           @RequestHeader(value = "X-User-Role", defaultValue = "") String actorRole,
                                           @RequestBody AfterSalesResumeRequestDTO request) {
        try {
            AfterSalesResumeCommand.ResumeAction action = AfterSalesResumeCommand.ResumeAction.valueOf(
                    request.action() == null ? "" : request.action().trim().toUpperCase()
            );
            return toResponse(afterSalesAgentService.resume(AfterSalesResumeCommand.of(
                    caseId,
                    request.checkpointId(),
                    action,
                    request.orderId(),
                    request.refundReason(),
                    actorId,
                    actorRole
            )));
        } catch (AfterSalesResumeConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage(), e);
        }
    }

    @GetMapping("/{caseId}")
    public AfterSalesRunResponseDTO query(@PathVariable String caseId,
                                          @RequestHeader("X-User-Id") String requesterId,
                                          @RequestHeader(value = "X-User-Role", defaultValue = "") String requesterRole) {
        AfterSalesCaseView view;
        try {
            view = afterSalesAgentService.query(caseId, requesterId, requesterRole)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "售后Case不存在"));
        } catch (SecurityException error) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, error.getMessage(), error);
        }
        return new AfterSalesRunResponseDTO(
                view.caseIdValue(),
                null,
                null,
                view.stage(),
                view.checkpointId(),
                view.nextNode(),
                null,
                view.terminalReason(),
                view.commandId(),
                Map.of()
        );
    }

    @DeleteMapping("/{caseId}")
    public Map<String, Object> cancel(@PathVariable String caseId,
                                      @RequestHeader("X-User-Id") String requesterId,
                                      @RequestParam(required = false) String reason) {
        try {
            boolean cancelled = afterSalesAgentService.cancel(caseId, requesterId, reason);
            return Map.of("caseId", caseId, "cancelled", cancelled);
        } catch (SecurityException error) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, error.getMessage(), error);
        }
    }

    private AfterSalesRunResponseDTO toResponse(AfterSalesRunResult result) {
        return new AfterSalesRunResponseDTO(
                result.caseIdValue(),
                result.turnIdValue(),
                result.runIdValue(),
                result.stage(),
                result.checkpointId(),
                result.nextNode(),
                result.waitingReason(),
                result.terminalReason(),
                result.commandId(),
                result.state()
        );
    }
}
