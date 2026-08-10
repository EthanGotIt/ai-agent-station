package cn.ethan.controller;

import cn.ethan.config.AgentRuntimeProperties;
import cn.ethan.core.agent.enums.OutputEventTypeEnum;
import cn.ethan.core.agent.enums.RouteTypeEnum;
import cn.ethan.core.agent.exception.RequestLifecycleException;
import cn.ethan.core.agent.exception.ReActExecutionException;
import cn.ethan.core.agent.exception.SessionQueueException;
import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.model.AgentResponseModel;
import cn.ethan.core.agent.model.OutputEventModel;
import cn.ethan.core.agent.model.QueuedExecutionModel;
import cn.ethan.core.agent.model.RequestHandleModel;
import cn.ethan.core.agent.service.AgentRuntimeService;
import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.workflow.enums.WorkflowRunStatusEnum;
import cn.ethan.core.workflow.model.WorkflowAnswerRequestModel;
import cn.ethan.core.workflow.model.WorkflowQuestionFieldModel;
import cn.ethan.core.workflow.model.WorkflowQuestionModel;
import cn.ethan.core.workflow.enums.WorkflowQuestionFieldTypeEnum;
import cn.ethan.core.workflow.model.WorkflowRunModel;
import cn.ethan.handler.AgentExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent 接口控制器测试：验证同步、SSE、取消、参数校验和冲突响应契约。
 *
 * @author ethan
 * @date 2026-08-05
 */
class AgentControllerTest {

    private static final String REQUEST_JSON = """
            {
              "requestId": "request-1",
              "sessionId": "session-1",
              "message": "现在几点"
            }
            """;

    private AgentRuntimeService runtimeService;
    private MockMvc mockMvc;

    @BeforeEach
    void createController() {
        runtimeService = mock(AgentRuntimeService.class);
        AgentController controller = new AgentController(
                runtimeService,
                new AgentRuntimeProperties(null, null, null, null)
        );
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AgentExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void synchronousAndSseEndpointsReturnSameFinalContent() throws Exception {
        AgentResponseModel response = response("当前时间：2026-08-05T08:00:00Z");
        when(runtimeService.handle(any(AgentRequestModel.class), eq("user-1")))
                .thenReturn(response);
        RequestHandleModel handle = new RequestHandleModel(
                "request-1",
                "user-1",
                "session-1",
                new CancellationToken()
        );
        when(runtimeService.submit(
                any(AgentRequestModel.class),
                eq("user-1"),
                any()
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<OutputEventModel> sink = invocation.getArgument(2, Consumer.class);
            sink.accept(new OutputEventModel(
                    OutputEventTypeEnum.PROGRESS,
                    "queued"
            ));
            sink.accept(new OutputEventModel(
                    OutputEventTypeEnum.CONTENT,
                    response.content()
            ));
            sink.accept(new OutputEventModel(
                    OutputEventTypeEnum.DONE,
                    response.status().name()
            ));
            return new QueuedExecutionModel(
                    handle,
                    CompletableFuture.completedFuture(response)
            );
        });

        mockMvc.perform(post("/api/v1/agent/chat")
                        .header("X-User-Id", "user-1")
                        .contentType("application/json")
                        .content(REQUEST_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(response.content()));

        MvcResult initialResult = mockMvc.perform(post("/api/v1/agent/chat/stream")
                        .header("X-User-Id", "user-1")
                        .contentType("application/json")
                        .content(REQUEST_JSON))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(initialResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data:" + response.content()
                )))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("reasoning")
                )));
    }

    @Test
    void invalidRequestReturnsStableError() throws Exception {
        mockMvc.perform(post("/api/v1/agent/chat")
                        .header("X-User-Id", "user-1")
                        .contentType("application/json")
                        .content("{\"requestId\":\"\",\"sessionId\":\"session-1\",\"message\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void chatPassesPerTurnMemoryOverridesToRuntime() throws Exception {
        when(runtimeService.handle(any(AgentRequestModel.class), eq("user-1")))
                .thenReturn(response("已处理"));

        mockMvc.perform(post("/api/v1/agent/chat")
                        .header("X-User-Id", "user-1")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"request-memory","sessionId":"session-1","message":"你好",
                                "memory":{"generate":true,"use":true}}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<AgentRequestModel> request = ArgumentCaptor.forClass(AgentRequestModel.class);
        verify(runtimeService).handle(request.capture(), eq("user-1"));
        assertTrue(request.getValue().memory().generationEnabled(false));
        assertTrue(request.getValue().memory().usageEnabled(false));
    }

    @Test
    void synchronousReactConfirmationRequiresStream() throws Exception {
        when(runtimeService.handle(any(AgentRequestModel.class), eq("user-1")))
                .thenThrow(new ReActExecutionException(
                        "REACT_CONFIRM_REQUIRES_STREAM", "confirmation requires stream"
                ));

        mockMvc.perform(post("/api/v1/agent/chat")
                        .header("X-User-Id", "user-1")
                        .contentType("application/json")
                        .content(REQUEST_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REACT_CONFIRM_REQUIRES_STREAM"));
    }

    @Test
    void requestIdConflictReturnsRelatedRequestId() throws Exception {
        when(runtimeService.handle(any(AgentRequestModel.class), eq("user-1")))
                .thenThrow(new RequestLifecycleException(
                        "REQUEST_ID_CONFLICT",
                        "requestId 在保留期内已存在",
                        "request-1"
                ));

        mockMvc.perform(post("/api/v1/agent/chat")
                        .header("X-User-Id", "user-1")
                        .contentType("application/json")
                        .content(REQUEST_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"))
                .andExpect(jsonPath("$.relatedRequestId").value("request-1"));
    }

    @Test
    void sseQueueFullReturnsHttp429BeforeStreamStarts() throws Exception {
        when(runtimeService.submit(
                any(AgentRequestModel.class),
                eq("user-1"),
                any()
        )).thenThrow(new SessionQueueException(
                        "SESSION_QUEUE_FULL",
                        "当前 Session 排队请求已满",
                        "request-active"
                ));

        mockMvc.perform(post("/api/v1/agent/chat/stream")
                        .header("X-User-Id", "user-1")
                        .contentType("application/json")
                        .content(REQUEST_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SESSION_QUEUE_FULL"))
                .andExpect(jsonPath("$.relatedRequestId").value("request-active"));
    }

    @Test
    void cancelRequiresMatchingUser() throws Exception {
        when(runtimeService.cancel("request-2", "user-1")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/agent/requests/request-2")
                        .header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("request-2"))
                .andExpect(jsonPath("$.cancelled").value(true));

        verify(runtimeService).cancel("request-2", "user-1");
    }

    @Test
    void toolInterventionDecisionUsesBypassRuntimeEndpoint() throws Exception {
        when(runtimeService.decideToolIntervention(any(), eq("user-1"))).thenReturn(true);

        mockMvc.perform(post("/api/v1/agent/requests/request-1/interventions/reply-1")
                        .header("X-User-Id", "user-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "sessionId": "session-1",
                                  "toolCallIds": ["tool-1"],
                                  "decision": "CONFIRM"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        verify(runtimeService).decideToolIntervention(any(), eq("user-1"));
    }

    @Test
    void answerEndpointReturnsExplicitWorkflowQuestionMetadata() throws Exception {
        AgentRequestModel request = new AgentRequestModel(
                "answer-request-1", "session-1", "回答工作流问题"
        );
        WorkflowQuestionModel question = new WorkflowQuestionModel(
                "question-1", "confirm_refund", "refund_confirmation", "确认退款申请",
                "请确认退款申请。", java.util.List.of(new WorkflowQuestionFieldModel(
                "decision", "退款申请", WorkflowQuestionFieldTypeEnum.CONFIRM, true,
                java.util.List.of("CONFIRM", "REJECT")
        ))
        );
        WorkflowRunModel run = new WorkflowRunModel(
                "run-1", "user-1", "session-1", "after_sales", "after-sales-refund", "v1",
                "APPLY", WorkflowRunStatusEnum.WAITING_USER_INPUT, "confirm_refund", 0,
                Map.of("orderId", "ORDER-PAID-001"), question, "", Instant.parse("2026-08-07T00:00:00Z"),
                Instant.parse("2026-08-07T00:00:00Z")
        );
        when(runtimeService.answer(any(WorkflowAnswerRequestModel.class), eq("user-1")))
                .thenReturn(AgentResponseModel.waitingUserInput(
                        request,
                        "after-sales-refund",
                        "after_sales",
                        "after-sales-refund",
                        "APPLY",
                        "请确认退款申请。",
                        question,
                        run
                ));

        mockMvc.perform(post("/api/v1/agent/workflow-runs/run-1/answers")
                        .header("X-User-Id", "user-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "requestId": "answer-request-1",
                                  "sessionId": "session-1",
                                  "questionId": "question-1",
                                  "checkpointId": "confirm_refund",
                                  "expectedVersion": 0,
                                  "answers": {"decision": "CONFIRM"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_USER_INPUT"))
                .andExpect(jsonPath("$.workflowRun.runId").value("run-1"))
                .andExpect(jsonPath("$.question.questionId").value("question-1"));
    }

    @Test
    void missingUserHeaderReturnsStableError() throws Exception {
        mockMvc.perform(post("/api/v1/agent/chat")
                        .contentType("application/json")
                        .content(REQUEST_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private AgentResponseModel response(String content) {
        AgentRequestModel request = new AgentRequestModel(
                "request-1",
                "session-1",
                "现在几点"
        );
        return AgentResponseModel.completed(
                request,
                RouteTypeEnum.ATOMIC,
                "clock",
                content
        );
    }
}
