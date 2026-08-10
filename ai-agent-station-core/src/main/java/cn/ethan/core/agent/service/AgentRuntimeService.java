package cn.ethan.core.agent.service;

import cn.ethan.core.agent.enums.AgentStatusEnum;
import cn.ethan.core.agent.enums.OutputEventTypeEnum;
import cn.ethan.core.agent.enums.RequestLifecycleStateEnum;
import cn.ethan.core.agent.enums.RouteTypeEnum;
import cn.ethan.core.agent.exception.ReActExecutionException;
import cn.ethan.core.agent.exception.SessionQueueException;
import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.model.AgentMemoryEntryModel;
import cn.ethan.core.agent.model.AgentResponseModel;
import cn.ethan.core.agent.enums.ConversationRoleEnum;
import cn.ethan.core.agent.model.ConversationMessageModel;
import cn.ethan.core.agent.model.OutputContextModel;
import cn.ethan.core.agent.model.OutputEventModel;
import cn.ethan.core.agent.model.QueuedExecutionModel;
import cn.ethan.core.agent.model.ReActResultModel;
import cn.ethan.core.agent.model.RequestHandleModel;
import cn.ethan.core.agent.model.RouteDecisionModel;
import cn.ethan.core.agent.model.ToolInterventionRequestModel;
import cn.ethan.core.agent.port.ReActExecutor;
import cn.ethan.core.agent.port.ConversationStore;
import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.agent.support.NoOpConversationStore;
import cn.ethan.core.agent.support.NoOpAgentMemoryStore;
import cn.ethan.core.agent.support.AgentMemoryExtractionCoordinator;
import cn.ethan.core.workflow.enums.WorkflowStatusEnum;
import cn.ethan.core.workflow.exception.WorkflowRunConflictException;
import cn.ethan.core.workflow.exception.WorkflowRunNotFoundException;
import cn.ethan.core.workflow.model.WorkflowContextModel;
import cn.ethan.core.workflow.model.WorkflowAnswerRequestModel;
import cn.ethan.core.workflow.model.WorkflowResultModel;
import cn.ethan.core.workflow.model.WorkflowRunModel;
import cn.ethan.core.workflow.port.ResumableWorkflowExecutor;
import cn.ethan.core.workflow.port.WorkflowExecutor;
import cn.ethan.core.workflow.port.WorkflowRunStore;
import cn.ethan.core.workflow.service.WorkflowRegistryService;
import cn.ethan.core.workflow.support.NoOpWorkflowRunStore;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 运行服务：统一排队并编排路由、可恢复 Workflow 与 ReAct。
 *
 * @author ethan
 * @date 2026-08-06
 */
public final class AgentRuntimeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRuntimeService.class);

    private final RequestLifecycleManager lifecycle;
    private final SessionExecutionQueueManager queueManager;
    private final AgentRouterService router;
    private final ReActExecutor react;
    private final WorkflowRegistryService workflows;
    private final OutputManager output;
    private final Clock clock;
    private final ConversationStore conversations;
    private final int historyMessages;
    private final int historyCharacters;
    private final WorkflowRunStore workflowRuns;
    private final AgentMemoryService memories;
    private final AgentMemoryExtractionCoordinator memoryExtraction;

    public AgentRuntimeService(
            RequestLifecycleManager lifecycle,
            SessionExecutionQueueManager queueManager,
            AgentRouterService router,
            ReActExecutor react,
            WorkflowRegistryService workflows,
            OutputManager output,
            Clock clock
    ) {
        this(lifecycle, queueManager, router, react, workflows, output, clock,
                new NoOpConversationStore(), 6, 12_000, new NoOpWorkflowRunStore(),
                new AgentMemoryService(false, new NoOpAgentMemoryStore(), clock), null);
    }

    public AgentRuntimeService(
            RequestLifecycleManager lifecycle,
            SessionExecutionQueueManager queueManager,
            AgentRouterService router,
            ReActExecutor react,
            WorkflowRegistryService workflows,
            OutputManager output,
            Clock clock,
            ConversationStore conversations,
            int historyMessages,
            int historyCharacters
    ) {
        this(lifecycle, queueManager, router, react, workflows, output, clock,
                conversations, historyMessages, historyCharacters, new NoOpWorkflowRunStore(),
                new AgentMemoryService(false, new NoOpAgentMemoryStore(), clock), null);
    }

    public AgentRuntimeService(
            RequestLifecycleManager lifecycle,
            SessionExecutionQueueManager queueManager,
            AgentRouterService router,
            ReActExecutor react,
            WorkflowRegistryService workflows,
            OutputManager output,
            Clock clock,
            ConversationStore conversations,
            int historyMessages,
            int historyCharacters,
            WorkflowRunStore workflowRuns
    ) {
        this(lifecycle, queueManager, router, react, workflows, output, clock, conversations,
                historyMessages, historyCharacters, workflowRuns,
                new AgentMemoryService(false, new NoOpAgentMemoryStore(), clock), null);
    }

    public AgentRuntimeService(
            RequestLifecycleManager lifecycle,
            SessionExecutionQueueManager queueManager,
            AgentRouterService router,
            ReActExecutor react,
            WorkflowRegistryService workflows,
            OutputManager output,
            Clock clock,
            ConversationStore conversations,
            int historyMessages,
            int historyCharacters,
            WorkflowRunStore workflowRuns,
            AgentMemoryService memories
    ) {
        this(lifecycle, queueManager, router, react, workflows, output, clock, conversations,
                historyMessages, historyCharacters, workflowRuns, memories, null);
    }

    public AgentRuntimeService(
            RequestLifecycleManager lifecycle,
            SessionExecutionQueueManager queueManager,
            AgentRouterService router,
            ReActExecutor react,
            WorkflowRegistryService workflows,
            OutputManager output,
            Clock clock,
            ConversationStore conversations,
            int historyMessages,
            int historyCharacters,
            WorkflowRunStore workflowRuns,
            AgentMemoryService memories,
            AgentMemoryExtractionCoordinator memoryExtraction
    ) {
        this.lifecycle = lifecycle;
        this.queueManager = queueManager;
        this.router = router;
        this.react = react;
        this.workflows = workflows;
        this.output = output;
        this.clock = clock;
        this.conversations = conversations == null ? new NoOpConversationStore() : conversations;
        this.historyMessages = Math.max(historyMessages, 0);
        this.historyCharacters = Math.max(historyCharacters, 1_000);
        this.workflowRuns = workflowRuns == null ? new NoOpWorkflowRunStore() : workflowRuns;
        this.memories = memories == null
                ? new AgentMemoryService(false, new NoOpAgentMemoryStore(), clock)
                : memories;
        this.memoryExtraction = memoryExtraction;
    }

    public AgentResponseModel handle(AgentRequestModel request, String userId) {
        return await(submit(request, userId, null));
    }

    public AgentResponseModel answer(WorkflowAnswerRequestModel request, String userId) {
        return await(submitAnswer(request, userId, null));
    }

    public QueuedExecutionModel submit(
            AgentRequestModel request,
            String userId,
            Consumer<OutputEventModel> sink
    ) {
        validateUserId(userId);
        RequestHandleModel handle = lifecycle.prepare(
                request.requestId(),
                userId,
                request.sessionId()
        );
        OutputContextModel outputContext = output.start(request.requestId());
        AtomicBoolean outputSettled = new AtomicBoolean(false);

        lifecycle.markQueued(request.requestId());
        output.emit(sink, OutputEventTypeEnum.PROGRESS, "queued");
        try {
            QueuedExecutionModel queuedExecution = queueManager.submit(
                    request,
                    handle,
                    () -> handleActive(
                            request,
                            userId,
                            handle,
                            sink,
                            outputContext,
                            outputSettled
                    )
            );
            CompletableFuture<AgentResponseModel> settledCompletion =
                    queuedExecution.completion().handle((response, failure) -> {
                        settleQueuedCompletion(
                                request,
                                response,
                                failure,
                                sink,
                                outputContext,
                                outputSettled
                        );
                        if (failure != null) {
                            throw new CompletionException(unwrap(failure));
                        }
                        return response;
                    });
            return new QueuedExecutionModel(handle, settledCompletion);
        } catch (RuntimeException submissionFailure) {
            lifecycle.fail(request.requestId());
            String errorCode = submissionFailure instanceof SessionQueueException queueFailure
                    ? queueFailure.getCode()
                    : "QUEUE_SUBMISSION_FAILED";
            settleFailure(outputContext, outputSettled, sink, errorCode);
            throw submissionFailure;
        }
    }

    public QueuedExecutionModel submitAnswer(
            WorkflowAnswerRequestModel answerRequest,
            String userId,
            Consumer<OutputEventModel> sink
    ) {
        validateUserId(userId);
        AgentRequestModel queueRequest = new AgentRequestModel(
                answerRequest.requestId(),
                answerRequest.sessionId(),
                workflowAnswerMessage(answerRequest),
                answerRequest.memory()
        );
        RequestHandleModel handle = lifecycle.prepare(
                queueRequest.requestId(),
                userId,
                queueRequest.sessionId()
        );
        OutputContextModel outputContext = output.start(queueRequest.requestId());
        AtomicBoolean outputSettled = new AtomicBoolean(false);

        lifecycle.markQueued(queueRequest.requestId());
        output.emit(sink, OutputEventTypeEnum.PROGRESS, "queued");
        try {
            QueuedExecutionModel queuedExecution = queueManager.submit(
                    queueRequest,
                    handle,
                    () -> handleActiveAnswer(
                            queueRequest,
                            answerRequest,
                            userId,
                            handle,
                            sink,
                            outputContext,
                            outputSettled
                    )
            );
            CompletableFuture<AgentResponseModel> settledCompletion =
                    queuedExecution.completion().handle((response, failure) -> {
                        settleQueuedCompletion(
                                queueRequest,
                                response,
                                failure,
                                sink,
                                outputContext,
                                outputSettled
                        );
                        if (failure != null) {
                            throw new CompletionException(unwrap(failure));
                        }
                        return response;
                    });
            return new QueuedExecutionModel(handle, settledCompletion);
        } catch (RuntimeException submissionFailure) {
            lifecycle.fail(queueRequest.requestId());
            String errorCode = submissionFailure instanceof SessionQueueException queueFailure
                    ? queueFailure.getCode()
                    : "QUEUE_SUBMISSION_FAILED";
            settleFailure(outputContext, outputSettled, sink, errorCode);
            throw submissionFailure;
        }
    }

    public boolean cancel(String requestId, String userId) {
        Optional<RequestHandleModel> ownedHandle = lifecycle.findOwned(requestId, userId);
        if (ownedHandle.isEmpty()) {
            return false;
        }
        RequestLifecycleStateEnum state = lifecycle.state(requestId);
        if (state == RequestLifecycleStateEnum.CANCELLED) {
            return true;
        }
        if (isTerminal(state)) {
            return false;
        }

        if (queueManager.cancelWaiting(requestId, userId)) {
            lifecycle.markCancelled(requestId);
            return true;
        }

        RequestHandleModel handle = ownedHandle.orElseThrow();
        if (lifecycle.cancelActive(requestId, userId)) {
            react.interrupt(requestId, handle.userId(), handle.sessionId());
            return true;
        }
        return false;
    }

    public RequestLifecycleStateEnum state(String requestId) {
        return lifecycle.state(requestId);
    }

    /**
     * 工具确认旁路不进入 FIFO，否则会与等待中的同一 ReAct 请求互相阻塞。
     */
    public boolean decideToolIntervention(ToolInterventionRequestModel request, String userId) {
        validateUserId(userId);
        return react.decide(request, userId);
    }

    private AgentResponseModel handleActive(
            AgentRequestModel request,
            String userId,
            RequestHandleModel handle,
            Consumer<OutputEventModel> sink,
            OutputContextModel outputContext,
            AtomicBoolean outputSettled
    ) {
        try {
            handle.token().throwIfCancelled();
            lifecycle.activate(request.requestId());
            output.emit(sink, OutputEventTypeEnum.PROGRESS, "request_started");

            List<ConversationMessageModel> history = conversations.recent(
                    userId, request.sessionId(), historyMessages, historyCharacters
            );
            RouteDecisionModel decision = router.route(request, userId, history, handle.token());
            AgentResponseModel response = executeDecision(request, userId, history, handle.token(), sink, decision);

            recordConversation(request, userId, response);
            recordMemory(request, userId, response);
            finish(handle, response.status());
            output.emit(sink, OutputEventTypeEnum.DONE, response.status().name());
            settleSuccess(outputContext, outputSettled, response);
            return response;
        } catch (java.util.concurrent.CancellationException cancelled) {
            lifecycle.markCancelled(request.requestId());
            output.emit(sink, OutputEventTypeEnum.ERROR, "CANCELLED");
            output.emit(sink, OutputEventTypeEnum.DONE, AgentStatusEnum.CANCELLED.name());
            AgentResponseModel response = AgentResponseModel.cancelled(request);
            settleSuccess(outputContext, outputSettled, response);
            return response;
        } catch (ReActExecutionException reactFailure) {
            lifecycle.fail(request.requestId());
            output.emit(sink, OutputEventTypeEnum.ERROR, reactFailure.getCode());
            output.emit(sink, OutputEventTypeEnum.DONE, AgentStatusEnum.FAILED.name());
            if ("REACT_CONFIRM_REQUIRES_STREAM".equals(reactFailure.getCode())) {
                settleFailure(outputContext, outputSettled, null, reactFailure.getCode());
                throw reactFailure;
            }
            AgentResponseModel response = AgentResponseModel.failed(
                    request,
                    RouteTypeEnum.REACT,
                    AgentRouterService.REACT_EXECUTOR_ID,
                    "复杂请求执行失败，请稍后重试。"
            );
            settleSuccess(outputContext, outputSettled, response);
            return response;
        } catch (RuntimeException failure) {
            lifecycle.fail(request.requestId());
            output.emit(sink, OutputEventTypeEnum.ERROR, "REQUEST_FAILED");
            output.emit(sink, OutputEventTypeEnum.DONE, AgentStatusEnum.FAILED.name());
            AgentResponseModel response = AgentResponseModel.failed(
                    request,
                    RouteTypeEnum.CLARIFY,
                    "runtime",
                    "请求处理失败，请稍后重试。"
            );
            settleSuccess(outputContext, outputSettled, response);
            return response;
        } finally {
            settleLifecycle(handle);
        }
    }

    private AgentResponseModel handleActiveAnswer(
            AgentRequestModel queueRequest,
            WorkflowAnswerRequestModel answerRequest,
            String userId,
            RequestHandleModel handle,
            Consumer<OutputEventModel> sink,
            OutputContextModel outputContext,
            AtomicBoolean outputSettled
    ) {
        try {
            handle.token().throwIfCancelled();
            lifecycle.activate(queueRequest.requestId());
            output.emit(sink, OutputEventTypeEnum.PROGRESS, "request_started");

            WorkflowRunModel run = workflowRuns.findOwned(
                    answerRequest.runId(), userId, answerRequest.sessionId()
            ).orElseThrow(() -> new WorkflowRunNotFoundException(answerRequest.runId()));
            WorkflowExecutor workflow = workflows.find(run.workflowId()).orElseThrow(
                    () -> new WorkflowRunNotFoundException(answerRequest.runId())
            );
            if (!(workflow instanceof ResumableWorkflowExecutor resumable)) {
                throw new WorkflowRunConflictException("workflow does not support answers");
            }

            output.emit(sink, OutputEventTypeEnum.ROUTE, RouteTypeEnum.WORKFLOW.name());
            WorkflowResultModel result = resumable.answer(
                    answerRequest, userId, handle.token(), workflowSuggestions(queueRequest, userId)
            );
            AgentResponseModel response = workflowResultResponse(
                    queueRequest,
                    userId,
                    workflow,
                    run.domainId(),
                    run.operation(),
                    result,
                    sink
            );
            recordConversation(queueRequest, userId, response);
            recordMemory(queueRequest, userId, response);
            finish(handle, response.status());
            output.emit(sink, OutputEventTypeEnum.DONE, response.status().name());
            settleSuccess(outputContext, outputSettled, response);
            return response;
        } catch (java.util.concurrent.CancellationException cancelled) {
            lifecycle.markCancelled(queueRequest.requestId());
            output.emit(sink, OutputEventTypeEnum.ERROR, "CANCELLED");
            output.emit(sink, OutputEventTypeEnum.DONE, AgentStatusEnum.CANCELLED.name());
            AgentResponseModel response = AgentResponseModel.cancelled(queueRequest);
            settleSuccess(outputContext, outputSettled, response);
            return response;
        } catch (WorkflowRunNotFoundException | WorkflowRunConflictException workflowFailure) {
            lifecycle.fail(queueRequest.requestId());
            output.emit(sink, OutputEventTypeEnum.ERROR, "WORKFLOW_ANSWER_CONFLICT");
            output.emit(sink, OutputEventTypeEnum.DONE, AgentStatusEnum.FAILED.name());
            settleFailure(outputContext, outputSettled, null, "WORKFLOW_ANSWER_CONFLICT");
            throw workflowFailure;
        } catch (RuntimeException failure) {
            lifecycle.fail(queueRequest.requestId());
            output.emit(sink, OutputEventTypeEnum.ERROR, "REQUEST_FAILED");
            output.emit(sink, OutputEventTypeEnum.DONE, AgentStatusEnum.FAILED.name());
            AgentResponseModel response = AgentResponseModel.failed(
                    queueRequest,
                    RouteTypeEnum.WORKFLOW,
                    "workflow-answer",
                    "恢复工作流失败，请稍后重试。"
            );
            settleSuccess(outputContext, outputSettled, response);
            return response;
        } finally {
            settleLifecycle(handle);
        }
    }

    private AgentResponseModel executeDecision(
            AgentRequestModel request,
            String userId,
            List<ConversationMessageModel> history,
            CancellationToken token,
            Consumer<OutputEventModel> sink,
            RouteDecisionModel decision
    ) {
        if (decision.routeType() != RouteTypeEnum.WORKFLOW) {
            output.emit(sink, OutputEventTypeEnum.ROUTE, decision.routeType().name());
        }
        return switch (decision.routeType()) {
            case CLARIFY -> AgentResponseModel.completed(
                    request,
                    RouteTypeEnum.CLARIFY,
                    "router",
                    "我还不确定你的需求，请说明要查询的对象或目标。"
            );
            case WORKFLOW -> runWorkflow(
                    request,
                    userId,
                    token,
                    sink,
                    decision.executorId(),
                    decision.domainId(),
                    decision.operation(),
                    decision.parameters()
            );
            case REACT -> runReact(request, userId, history, token, sink);
            case ATOMIC -> AgentResponseModel.completed(
                    request,
                    RouteTypeEnum.ATOMIC,
                    decision.executorId(),
                    "当前时间：" + Instant.now(clock)
            );
        };
    }

    private AgentResponseModel runWorkflow(
            AgentRequestModel request,
            String userId,
            CancellationToken token,
            Consumer<OutputEventModel> sink,
            String workflowId,
            String domainId,
            String operation,
            Map<String, String> parameters
    ) {
        token.throwIfCancelled();
        output.emit(sink, OutputEventTypeEnum.ROUTE, RouteTypeEnum.WORKFLOW.name());

        WorkflowExecutor workflow = workflows.find(workflowId).orElseThrow(
                () -> new IllegalArgumentException("workflow is not registered: " + workflowId)
        );
        WorkflowResultModel result = workflow.execute(
                new WorkflowContextModel(
                        request, userId, token, workflowParameters(request, userId, operation, parameters)
                )
        );
        return workflowResultResponse(request, userId, workflow, domainId, operation, result, sink);
    }

    private AgentResponseModel workflowResultResponse(
            AgentRequestModel request,
            String userId,
            WorkflowExecutor workflow,
            String domainId,
            String operation,
            WorkflowResultModel result,
            Consumer<OutputEventModel> sink
    ) {
        if (result.status() == WorkflowStatusEnum.WAITING_USER_INPUT) {
            WorkflowRunModel workflowRun = result.context() == null
                    ? null
                    : (WorkflowRunModel) result.context().value("workflowRun");
            if (workflowRun == null || result.question() == null) {
                return AgentResponseModel.failed(
                        request,
                        RouteTypeEnum.WORKFLOW,
                        result.workflowId(),
                        "工作流 QuestionCard 状态不完整。"
                );
            }
            output.emit(sink, OutputEventTypeEnum.CONTENT, result.content());
            output.emitResult(sink, result.structuredResult());
            output.emitWorkflowQuestion(sink, result.question(), workflowRun);
            return AgentResponseModel.waitingUserInput(
                    request,
                    result.workflowId(),
                    domainId.isBlank() ? workflow.descriptor().domainId() : domainId,
                    result.workflowId(),
                    operation,
                    result.content(),
                    result.question(),
                    workflowRun
            );
        }
        output.emit(sink, OutputEventTypeEnum.CONTENT, result.content());
        output.emitResult(sink, result.structuredResult());
        WorkflowRunModel workflowRun = result.context() == null
                ? null
                : (WorkflowRunModel) result.context().value("workflowRun");
        return result.status() == WorkflowStatusEnum.COMPLETED
                ? workflowRun == null ? AgentResponseModel.completed(
                        request,
                        RouteTypeEnum.WORKFLOW,
                        result.workflowId(),
                        workflow.descriptor().domainId(),
                        result.workflowId(),
                        operation,
                        result.content(),
                        result.structuredResult(),
                        0,
                        0
                ) : AgentResponseModel.completedWorkflowRun(
                        request,
                        result.workflowId(),
                        domainId.isBlank() ? workflow.descriptor().domainId() : domainId,
                        result.workflowId(),
                        operation,
                        result.content(),
                        result.structuredResult(),
                        workflowRun
                )
                : AgentResponseModel.failed(
                        request,
                        RouteTypeEnum.WORKFLOW,
                        result.workflowId(),
                        result.content()
                );
    }

    private Map<String, Object> workflowParameters(
            AgentRequestModel request, String userId, String operation, Map<String, String> parameters
    ) {
        Map<String, Object> initial = new java.util.LinkedHashMap<>();
        if (parameters != null) {
            initial.putAll(parameters);
        }
        if (operation != null && !operation.isBlank()) {
            initial.put("operation", operation);
        }
        initial.put("memorySuggestions", workflowSuggestions(request, userId));
        return Map.copyOf(initial);
    }

    private String workflowAnswerMessage(WorkflowAnswerRequestModel answerRequest) {
        StringBuilder message = new StringBuilder("工作流用户回答：");
        answerRequest.answers().forEach((key, value) -> message.append(key).append('=').append(value).append(';'));
        return message.toString();
    }

    private Map<String, Object> workflowSuggestions(AgentRequestModel request, String userId) {
        Map<String, Object> suggestions = new java.util.LinkedHashMap<>();
        memories.workflowSuggestion(userId, request.sessionId(), request.memory(), "order.id")
                .ifPresent(entry -> suggestions.put("order.id", entry));
        memories.workflowSuggestion(userId, request.sessionId(), request.memory(), "refund.reason")
                .ifPresent(entry -> suggestions.put("refund.reason", entry));
        return Map.copyOf(suggestions);
    }

    private AgentResponseModel runReact(
            AgentRequestModel request,
            String userId,
            List<ConversationMessageModel> history,
            CancellationToken token,
            Consumer<OutputEventModel> sink
    ) {
        token.throwIfCancelled();
        ReActResultModel result = react.execute(
                request,
                userId,
                history == null ? List.of() : history,
                memories.forReAct(userId, request.sessionId(), request.memory()),
                token,
                sink == null ? null : event -> output.emit(sink, event)
        );
        token.throwIfCancelled();
        String content = result.finalContent();
        if (sink == null) {
            output.emit(null, OutputEventTypeEnum.CONTENT, content);
        }
        return AgentResponseModel.completed(
                request,
                RouteTypeEnum.REACT,
                AgentRouterService.REACT_EXECUTOR_ID,
                content,
                result.inputTokens(),
                result.outputTokens()
        );
    }

    private void recordConversation(
            AgentRequestModel request,
            String userId,
            AgentResponseModel response
    ) {
        if (response.status() == AgentStatusEnum.CANCELLED) {
            return;
        }
        try {
            conversations.append(userId, request.sessionId(), new ConversationMessageModel(
                    ConversationRoleEnum.USER, request.normalizedMessage()
            ));
            conversations.append(userId, request.sessionId(), new ConversationMessageModel(
                    ConversationRoleEnum.ASSISTANT, response.content()
            ));
        } catch (RuntimeException failure) {
            // 会话持久化不可用不能改变当前确定性执行或 ReAct 的最终结果。
            LOGGER.warn(
                    "Agent 会话历史持久化失败，requestId={}，exception={}",
                    request.requestId(),
                    failure.getClass().getSimpleName()
            );
        }
    }

    private void recordMemory(AgentRequestModel request, String userId, AgentResponseModel response) {
        try {
            if (memoryExtraction != null) {
                memories.extractionInput(request, userId, response).ifPresent(memoryExtraction::schedule);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("Agent 记忆记录失败，requestId={}，exception={}", request.requestId(),
                    failure.getClass().getSimpleName());
        }
    }

    private AgentResponseModel await(QueuedExecutionModel queuedExecution) {
        try {
            return queuedExecution.completion().join();
        } catch (CompletionException failure) {
            Throwable cause = unwrap(failure);
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalStateException("queued execution failed", cause);
        }
    }

    private void settleQueuedCompletion(
            AgentRequestModel request,
            AgentResponseModel response,
            Throwable failure,
            Consumer<OutputEventModel> sink,
            OutputContextModel outputContext,
            AtomicBoolean outputSettled
    ) {
        if (outputSettled.get()) {
            return;
        }
        if (failure != null) {
            Throwable cause = unwrap(failure);
            String errorCode = cause instanceof SessionQueueException queueFailure
                    ? queueFailure.getCode()
                    : "QUEUE_EXECUTION_FAILED";
            lifecycle.fail(request.requestId());
            settleFailure(outputContext, outputSettled, sink, errorCode);
            return;
        }
        if (response != null && response.status() == AgentStatusEnum.CANCELLED) {
            lifecycle.markCancelled(request.requestId());
            output.emit(sink, OutputEventTypeEnum.ERROR, "CANCELLED");
            output.emit(sink, OutputEventTypeEnum.DONE, AgentStatusEnum.CANCELLED.name());
            settleSuccess(outputContext, outputSettled, response);
        }
    }

    private void settleSuccess(
            OutputContextModel outputContext,
            AtomicBoolean outputSettled,
            AgentResponseModel response
    ) {
        if (outputSettled.compareAndSet(false, true)) {
            output.complete(
                    outputContext,
                    response.executorId(),
                    response.status(),
                    response.inputTokens(),
                    response.outputTokens()
            );
        }
    }

    private void settleFailure(
            OutputContextModel outputContext,
            AtomicBoolean outputSettled,
            Consumer<OutputEventModel> sink,
            String errorCode
    ) {
        if (outputSettled.compareAndSet(false, true)) {
            output.error(outputContext, errorCode);
            output.emit(sink, OutputEventTypeEnum.ERROR, errorCode);
            output.emit(sink, OutputEventTypeEnum.DONE, AgentStatusEnum.FAILED.name());
            output.complete(outputContext, "queue", AgentStatusEnum.FAILED, 0, 0);
        }
    }

    private void finish(RequestHandleModel handle, AgentStatusEnum status) {
        if (handle.token().isCancelled() || status == AgentStatusEnum.CANCELLED) {
            lifecycle.markCancelled(handle.requestId());
        } else if (status == AgentStatusEnum.FAILED) {
            lifecycle.fail(handle.requestId());
        } else {
            lifecycle.complete(handle.requestId());
        }
    }

    private void settleLifecycle(RequestHandleModel handle) {
        RequestLifecycleStateEnum state = lifecycle.state(handle.requestId());
        if (handle.token().isCancelled()
                || state == RequestLifecycleStateEnum.CANCELLING) {
            lifecycle.markCancelled(handle.requestId());
        } else if (state == RequestLifecycleStateEnum.PREPARED
                || state == RequestLifecycleStateEnum.QUEUED
                || state == RequestLifecycleStateEnum.ACTIVE) {
            lifecycle.fail(handle.requestId());
        }
    }

    private void validateUserId(String userId) {
        if (userId == null || userId.isBlank() || userId.length() > 128) {
            throw new IllegalArgumentException("userId is required and must not exceed 128");
        }
    }

    private boolean isTerminal(RequestLifecycleStateEnum state) {
        return state == RequestLifecycleStateEnum.COMPLETED
                || state == RequestLifecycleStateEnum.FAILED;
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
