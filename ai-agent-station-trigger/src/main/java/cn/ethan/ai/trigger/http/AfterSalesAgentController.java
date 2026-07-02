package cn.ethan.ai.trigger.http;

import cn.ethan.ai.api.dto.AfterSalesResumeRequestDTO;
import cn.ethan.ai.api.dto.AfterSalesRunRequestDTO;
import cn.ethan.ai.api.dto.AfterSalesRunResponseDTO;
import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesResumeCommand;
import cn.ethan.ai.domain.agent.model.AfterSalesRunCommand;
import cn.ethan.ai.domain.agent.model.AfterSalesRunResult;
import cn.ethan.ai.domain.agent.service.AfterSalesAgentService;
import cn.ethan.ai.domain.agent.service.exception.AfterSalesResumeConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/after-sales/runs")
@CrossOrigin(origins = "*")
public class AfterSalesAgentController {

    private final AfterSalesAgentService afterSalesAgentService;

    public AfterSalesAgentController(AfterSalesAgentService afterSalesAgentService) {
        this.afterSalesAgentService = afterSalesAgentService;
    }

    @PostMapping
    public AfterSalesRunResponseDTO start(@RequestBody AfterSalesRunRequestDTO request) {
        return toResponse(afterSalesAgentService.start(new AfterSalesRunCommand(
                request.getUserId(),
                request.getSessionId(),
                request.getMessage(),
                request.getOrderId(),
                request.getRefundReason()
        )));
    }

    @PostMapping("/{runId}/resume")
    public AfterSalesRunResponseDTO resume(@PathVariable String runId,
                                           @RequestBody AfterSalesResumeRequestDTO request) {
        try {
            AfterSalesResumeCommand.ResumeAction action = AfterSalesResumeCommand.ResumeAction.valueOf(
                    request.getAction() == null ? "" : request.getAction().trim().toUpperCase()
            );
            return toResponse(afterSalesAgentService.resume(new AfterSalesResumeCommand(
                    runId,
                    request.getCheckpointId(),
                    action,
                    request.getOrderId(),
                    request.getRefundReason()
            )));
        } catch (AfterSalesResumeConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping("/{runId}")
    public AfterSalesRunResponseDTO query(@PathVariable String runId) {
        AfterSalesCaseView view = afterSalesAgentService.query(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "售后运行不存在"));
        return AfterSalesRunResponseDTO.builder()
                .runId(view.runId())
                .caseId(view.caseId())
                .stage(view.stage())
                .checkpointId(view.checkpointId())
                .nextNode(view.nextNode())
                .terminalReason(view.terminalReason())
                .commandId(view.commandId())
                .state(Map.of())
                .build();
    }

    @DeleteMapping("/{runId}")
    public Map<String, Object> cancel(@PathVariable String runId,
                                      @RequestParam(required = false) String reason) {
        boolean cancelled = afterSalesAgentService.cancel(runId, reason);
        return Map.of("runId", runId, "cancelled", cancelled);
    }

    private AfterSalesRunResponseDTO toResponse(AfterSalesRunResult result) {
        return AfterSalesRunResponseDTO.builder()
                .runId(result.runId())
                .caseId(result.caseId())
                .stage(result.stage())
                .checkpointId(result.checkpointId())
                .nextNode(result.nextNode())
                .waitingReason(result.waitingReason())
                .terminalReason(result.terminalReason())
                .commandId(result.commandId())
                .state(result.state())
                .build();
    }
}
