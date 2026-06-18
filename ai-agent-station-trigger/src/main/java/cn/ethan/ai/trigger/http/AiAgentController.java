package cn.ethan.ai.trigger.http;

import cn.ethan.ai.api.IAiAgentService;
import cn.ethan.ai.api.dto.AgentRunCancelResponseDTO;
import cn.ethan.ai.api.dto.AgentContextBoundaryResponseDTO;
import cn.ethan.ai.api.dto.AgentRunDetailResponseDTO;
import cn.ethan.ai.api.dto.AgentRunLifecycleResponseDTO;
import cn.ethan.ai.api.dto.AgentStepRunResponseDTO;
import cn.ethan.ai.api.dto.AgentExecuteRequestDTO;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunDetailVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunLifecycleVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRunRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentContextBoundaryVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentStepRunStatusEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.StreamTransportTypeEnumVO;
import cn.ethan.ai.domain.agent.service.IAgentDispatchService;
import cn.ethan.ai.domain.agent.service.IAgentRunService;
import cn.ethan.ai.domain.agent.service.execute.runtime.AgentContextBoundaryService;
import cn.ethan.ai.domain.agent.service.execute.runtime.AgentExecutionException;
import cn.ethan.ai.trigger.http.adapter.ResponseBodyEmitterStreamPort;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.Collections;

/**
 * Agent 统一执行入口
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class AiAgentController implements IAiAgentService {

    @Resource
    private IAgentDispatchService agentDispatchService;

    @Resource
    private IAgentRunService agentRunService;

    @Resource
    private AgentContextBoundaryService agentContextBoundaryService;

    @Override
    @RequestMapping(value = "execute", method = RequestMethod.POST)
    public ResponseBodyEmitter execute(@RequestBody AgentExecuteRequestDTO request, HttpServletResponse response) {
        log.info("Agent 流式执行请求开始，请求信息：{}", JSON.toJSONString(request));

        try {
            response.setContentType("application/x-ndjson");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache, no-transform");
            response.setHeader("Connection", "keep-alive");

            validateRequest(request);

            ResponseBodyEmitter emitter = new ResponseBodyEmitter(10 * 60 * 1000L);
            ExecuteCommandEntity executeCommandEntity = ExecuteCommandEntity.builder()
                    .aiAgentId(request.getAiAgentId())
                    .message(request.getMessage())
                    .sessionId(request.getSessionId())
                    .maxStep(request.getMaxStep())
                    .streamProtocol(StreamTransportTypeEnumVO.STREAMABLE_HTTP.getCode())
                    .build();

            agentDispatchService.dispatch(executeCommandEntity, new ResponseBodyEmitterStreamPort(emitter));
            return emitter;
        } catch (Exception e) {
            log.error("Agent 请求处理异常：{}", e.getMessage(), e);
            ResponseBodyEmitter errorEmitter = new ResponseBodyEmitter();
            try {
                sendError(errorEmitter, "请求处理异常：" + e.getMessage(), request, resolveRunId(e));
            } catch (Exception ex) {
                log.error("发送错误信息失败：{}", ex.getMessage(), ex);
            }
            return errorEmitter;
        }
    }

    @RequestMapping(value = "run/{runId}", method = RequestMethod.GET)
    public AgentRunDetailResponseDTO queryRun(@PathVariable("runId") String runId) {
        AgentRunDetailVO detail = agentRunService.queryRun(runId);
        if (detail == null) {
            throw new IllegalArgumentException("运行记录不存在，runId=" + runId);
        }
        return AgentRunDetailResponseDTO.builder()
                .runId(detail.getRunId())
                .agentId(detail.getAgentId())
                .sessionId(detail.getSessionId())
                .userMessage(detail.getUserMessage())
                .status(detail.getStatus() == null ? null : detail.getStatus().name())
                .finalSummary(detail.getFinalSummary())
                .errorMessage(detail.getErrorMessage())
                .cancelReason(detail.getCancelReason())
                .contextOriginalChars(detail.getContextOriginalChars())
                .contextCompressedChars(detail.getContextCompressedChars())
                .contextSummary(detail.getContextSummary())
                .startTime(detail.getStartTime())
                .endTime(detail.getEndTime())
                .createTime(detail.getCreateTime())
                .updateTime(detail.getUpdateTime())
                .lifecycle(toLifecycleDto(detail.getLifecycle()))
                .contextBoundary(toContextBoundaryDto(buildPersistedContextBoundary(detail)))
                .steps(detail.getSteps() == null ? Collections.emptyList() : detail.getSteps().stream().map(this::toStepDto).toList())
                .build();
    }

    @RequestMapping(value = "run/{runId}/cancel", method = RequestMethod.POST)
    public AgentRunCancelResponseDTO cancelRun(@PathVariable("runId") String runId,
                                               @RequestParam(value = "reason", required = false) String reason) {
        boolean cancelled = agentRunService.cancelRun(runId, reason);
        return AgentRunCancelResponseDTO.builder()
                .runId(runId)
                .cancelled(cancelled)
                .message(cancelled ? "已提交取消请求" : "当前运行无法取消，可能已结束或不存在")
                .build();
    }

    private void validateRequest(AgentExecuteRequestDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (StringUtils.isBlank(request.getAiAgentId())) {
            throw new IllegalArgumentException("aiAgentId 不能为空");
        }
        if (StringUtils.isBlank(request.getMessage())) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (StringUtils.isBlank(request.getSessionId())) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (request.getMaxStep() != null && request.getMaxStep() <= 0) {
            throw new IllegalArgumentException("maxStep 必须大于 0");
        }
    }

    private void sendError(ResponseBodyEmitter emitter, String content, AgentExecuteRequestDTO request, String runId) throws Exception {
        AgentExecuteResultEntity errorResult = AgentExecuteResultEntity.createErrorResult(
                content,
                request == null ? null : request.getSessionId(),
                runId
        );
        emitter.send(JSON.toJSONString(errorResult) + "\n");
        emitter.complete();
    }

    private String resolveRunId(Exception e) {
        if (e instanceof AgentExecutionException executionException) {
            return executionException.getRunId();
        }
        return null;
    }

    private AgentStepRunResponseDTO toStepDto(AgentStepRunRecordVO step) {
        return AgentStepRunResponseDTO.builder()
                .stepId(step.getStepId())
                .stepName(step.getStepName())
                .stepOrder(step.getStepOrder())
                .stepType(step.getStepType())
                .status(step.getStatus() == null ? null : step.getStatus().name())
                .outputSummary(step.getOutputSummary())
                .errorMessage(step.getErrorMessage())
                .terminalReason(resolveStepTerminalReason(step))
                .costMillis(step.getCostMillis())
                .startTime(step.getStartTime())
                .endTime(step.getEndTime())
                .build();
    }

    private AgentRunLifecycleResponseDTO toLifecycleDto(AgentRunLifecycleVO lifecycle) {
        if (lifecycle == null) {
            return null;
        }
        return AgentRunLifecycleResponseDTO.builder()
                .runtimePhase(lifecycle.getRuntimePhase())
                .currentStepId(lifecycle.getCurrentStepId())
                .terminalReason(lifecycle.getTerminalReason())
                .trackedStepCount(lifecycle.getTrackedStepCount())
                .completedStepCount(lifecycle.getCompletedStepCount())
                .failedStepCount(lifecycle.getFailedStepCount())
                .skippedStepCount(lifecycle.getSkippedStepCount())
                .cancelledStepCount(lifecycle.getCancelledStepCount())
                .contextCompacted(lifecycle.getContextCompacted())
                .build();
    }

    private AgentContextBoundaryResponseDTO toContextBoundaryDto(AgentContextBoundaryVO boundary) {
        if (boundary == null) {
            return null;
        }
        return AgentContextBoundaryResponseDTO.builder()
                .sessionId(boundary.getSessionId())
                .projectRuleScope(boundary.getProjectRuleScope())
                .userPreferenceScope(boundary.getUserPreferenceScope())
                .conversationScope(boundary.getConversationScope())
                .projectRules(boundary.getProjectRules())
                .userPreferences(boundary.getUserPreferences())
                .sessionContextSummary(boundary.getSessionContextSummary())
                .runContextSummary(boundary.getRunContextSummary())
                .longTermMemoryEnabled(boundary.isLongTermMemoryEnabled())
                .build();
    }

    private AgentContextBoundaryVO buildPersistedContextBoundary(AgentRunDetailVO detail) {
        AgentContextBoundaryVO boundary = agentContextBoundaryService.buildBoundary(
                detail.getSessionId(),
                detail.getUserMessage(),
                detail.getSessionContextSummary()
        );
        AgentContextBoundaryService.attachRunSummary(boundary, detail.getContextSummary());
        return boundary;
    }

    private String resolveStepTerminalReason(AgentStepRunRecordVO step) {
        if (step == null || step.getStatus() == null) {
            return "";
        }
        if (AgentStepRunStatusEnumVO.FAILED == step.getStatus()) {
            return StringUtils.defaultString(step.getErrorMessage());
        }
        if (AgentStepRunStatusEnumVO.SKIPPED == step.getStatus()
                || AgentStepRunStatusEnumVO.CANCELLED == step.getStatus()) {
            return StringUtils.defaultIfBlank(step.getOutputSummary(), step.getErrorMessage());
        }
        return "";
    }
}
