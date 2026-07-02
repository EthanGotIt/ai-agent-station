package cn.ethan.ai.domain.agent.service;

import cn.ethan.ai.domain.agent.adapter.repository.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.adapter.port.IAfterSalesToolPort;
import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundResult;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.model.valobj.enums.ToolErrorType;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.StateSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

public final class AfterSalesGraphRuntime {

    public static final String INTAKE = "intake";
    public static final String ROUTE_REQUEST = "route_request";
    public static final String DECIDE_TOOL = "decide_tool";
    public static final String VALIDATE_TOOL = "validate_tool";
    public static final String EXECUTE_TOOL = "execute_tool";
    public static final String EVALUATE_POLICY = "evaluate_policy";
    public static final String READY_FOR_APPROVAL = "ready_for_approval";
    public static final String EXECUTE_REFUND = "execute_refund";
    public static final String VERIFY = "verify";
    public static final String NEED_USER_INPUT = "need_user_input";
    public static final String REPAIR_TOOL_INPUT = "repair_tool_input";
    public static final String RETRY_TOOL = "retry_tool";
    public static final String RELOAD_TOOL_STATE = "reload_tool_state";
    public static final String REJECTED = "rejected";
    public static final String COMPLETED = "completed";

    private final RefundEligibilityPolicy eligibilityPolicy;
    private final ToolRecoveryPolicy recoveryPolicy;
    private final AfterSalesToolContractValidator contractValidator;
    private final ToolErrorClassifier errorClassifier;
    private final IAfterSalesToolPort toolPort;
    private final IAfterSalesRepository repository;
    private final Executor ioExecutor;
    private final CompiledGraph<AfterSalesAgentState> graph;

    public AfterSalesGraphRuntime() throws GraphStateException {
        this(new MemorySaver(), unavailableToolPort(), unavailableRepository(), Runnable::run);
    }

    public AfterSalesGraphRuntime(BaseCheckpointSaver checkpointSaver,
                                  IAfterSalesToolPort toolPort,
                                  IAfterSalesRepository repository) throws GraphStateException {
        this(checkpointSaver, toolPort, repository, Runnable::run);
    }

    public AfterSalesGraphRuntime(BaseCheckpointSaver checkpointSaver,
                                  IAfterSalesToolPort toolPort,
                                  IAfterSalesRepository repository,
                                  Executor ioExecutor) throws GraphStateException {
        this.eligibilityPolicy = new RefundEligibilityPolicy();
        this.recoveryPolicy = new ToolRecoveryPolicy();
        this.contractValidator = new AfterSalesToolContractValidator();
        this.errorClassifier = new ToolErrorClassifier();
        this.toolPort = toolPort;
        this.repository = repository;
        this.ioExecutor = ioExecutor;
        this.graph = buildGraph(checkpointSaver);
    }

    public AfterSalesAgentState execute(Map<String, Object> input, String threadId) {
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        return graph.invoke(input, config)
                .orElseThrow(() -> new IllegalStateException("After-sales graph returned no state"));
    }

    public AfterSalesAgentState resume(Map<String, Object> update, String threadId, String checkpointId) {
        RunnableConfig.Builder config = RunnableConfig.builder().threadId(threadId);
        if (checkpointId != null && !checkpointId.isBlank()) {
            config.checkPointId(checkpointId);
        }
        return graph.invoke(GraphInput.resume(update == null ? Map.of() : update), config.build())
                .orElseThrow(() -> new IllegalStateException("After-sales graph resume returned no state"));
    }

    public Optional<StateSnapshot<AfterSalesAgentState>> currentSnapshot(String threadId) {
        return graph.stateOf(RunnableConfig.builder().threadId(threadId).build());
    }

    public CompiledGraph<AfterSalesAgentState> compiledGraph() {
        return graph;
    }

    public int historySize(String threadId) {
        return graph.getStateHistory(RunnableConfig.builder().threadId(threadId).build()).size();
    }

    private CompiledGraph<AfterSalesAgentState> buildGraph(BaseCheckpointSaver checkpointSaver)
            throws GraphStateException {
        StateGraph<AfterSalesAgentState> stateGraph = new StateGraph<>(AfterSalesAgentState::new);

        stateGraph.addNode(INTAKE, node_async(state -> stage(AfterSalesStage.INTAKE)));
        stateGraph.addNode(ROUTE_REQUEST, node_async(this::routeRequest));
        stateGraph.addNode(DECIDE_TOOL, ioNode(this::decideTool));
        stateGraph.addNode(VALIDATE_TOOL, node_async(this::validateTool));
        stateGraph.addNode(EXECUTE_TOOL, ioNode(this::executeTool));
        stateGraph.addNode(EVALUATE_POLICY, node_async(this::evaluatePolicy));
        stateGraph.addNode(READY_FOR_APPROVAL, node_async(state -> stage(AfterSalesStage.READY_FOR_APPROVAL)));
        stateGraph.addNode(EXECUTE_REFUND, ioNode(this::executeRefund));
        stateGraph.addNode(VERIFY, ioNode(this::verifyRefund));
        stateGraph.addNode(NEED_USER_INPUT, node_async(state -> stage(AfterSalesStage.NEED_USER_INPUT)));
        stateGraph.addNode(REPAIR_TOOL_INPUT, node_async(this::repairToolInput));
        stateGraph.addNode(RETRY_TOOL, node_async(this::retryTool));
        stateGraph.addNode(RELOAD_TOOL_STATE, node_async(this::reloadToolState));
        stateGraph.addNode(REJECTED, node_async(state -> stage(AfterSalesStage.REJECTED)));
        stateGraph.addNode(COMPLETED, node_async(state -> stage(AfterSalesStage.COMPLETED)));

        stateGraph.addEdge(START, INTAKE);
        stateGraph.addEdge(INTAKE, ROUTE_REQUEST);
        addRouteEdges(stateGraph, ROUTE_REQUEST, Map.of(
                DECIDE_TOOL, DECIDE_TOOL,
                EVALUATE_POLICY, EVALUATE_POLICY,
                NEED_USER_INPUT, NEED_USER_INPUT,
                REPAIR_TOOL_INPUT, REPAIR_TOOL_INPUT,
                RETRY_TOOL, RETRY_TOOL,
                RELOAD_TOOL_STATE, RELOAD_TOOL_STATE,
                REJECTED, REJECTED
        ));
        addRouteEdges(stateGraph, DECIDE_TOOL, Map.of(
                VALIDATE_TOOL, VALIDATE_TOOL,
                NEED_USER_INPUT, NEED_USER_INPUT,
                REPAIR_TOOL_INPUT, REPAIR_TOOL_INPUT,
                RETRY_TOOL, RETRY_TOOL,
                RELOAD_TOOL_STATE, RELOAD_TOOL_STATE,
                REJECTED, REJECTED
        ));
        addRouteEdges(stateGraph, VALIDATE_TOOL, Map.of(
                EXECUTE_TOOL, EXECUTE_TOOL,
                REPAIR_TOOL_INPUT, REPAIR_TOOL_INPUT,
                REJECTED, REJECTED
        ));
        addRouteEdges(stateGraph, EXECUTE_TOOL, Map.of(
                EVALUATE_POLICY, EVALUATE_POLICY,
                REPAIR_TOOL_INPUT, REPAIR_TOOL_INPUT,
                RETRY_TOOL, RETRY_TOOL,
                RELOAD_TOOL_STATE, RELOAD_TOOL_STATE,
                REJECTED, REJECTED
        ));
        addRouteEdges(stateGraph, EVALUATE_POLICY, Map.of(
                READY_FOR_APPROVAL, READY_FOR_APPROVAL,
                NEED_USER_INPUT, NEED_USER_INPUT,
                REJECTED, REJECTED,
                COMPLETED, COMPLETED
        ));
        addRouteEdges(stateGraph, REPAIR_TOOL_INPUT, Map.of(DECIDE_TOOL, DECIDE_TOOL, END, END));
        addRouteEdges(stateGraph, RETRY_TOOL, Map.of(EXECUTE_TOOL, EXECUTE_TOOL, END, END));
        addRouteEdges(stateGraph, RELOAD_TOOL_STATE, Map.of(EXECUTE_TOOL, EXECUTE_TOOL, END, END));
        stateGraph.addEdge(NEED_USER_INPUT, ROUTE_REQUEST);
        stateGraph.addEdge(READY_FOR_APPROVAL, EXECUTE_REFUND);
        addRouteEdges(stateGraph, EXECUTE_REFUND, Map.of(VERIFY, VERIFY, REJECTED, REJECTED));
        addRouteEdges(stateGraph, VERIFY, Map.of(COMPLETED, COMPLETED, REJECTED, REJECTED));
        stateGraph.addEdge(REJECTED, END);
        stateGraph.addEdge(COMPLETED, END);

        return stateGraph.compile(CompileConfig.builder()
                .checkpointSaver(checkpointSaver)
                .interruptAfter(NEED_USER_INPUT, READY_FOR_APPROVAL)
                .releaseThread(false)
                .recursionLimit(24)
                .graphId("durable-after-sales-refund")
                .build());
    }

    private void addRouteEdges(StateGraph<AfterSalesAgentState> graphDefinition,
                               String source,
                               Map<String, String> routes) throws GraphStateException {
        graphDefinition.addConditionalEdges(source,
                edge_async(state -> state.text(AfterSalesAgentState.ROUTE)), routes);
    }

    private org.bsc.langgraph4j.action.AsyncNodeAction<AfterSalesAgentState> ioNode(
            Function<AfterSalesAgentState, Map<String, Object>> action) {
        return state -> CompletableFuture.supplyAsync(() -> action.apply(state), ioExecutor);
    }

    private Map<String, Object> routeRequest(AfterSalesAgentState state) {
        if (state.hasText(AfterSalesAgentState.ERROR_TYPE)) {
            return recoveryUpdate(state, state.errorType(), state.text(AfterSalesAgentState.DECISION_REASON));
        }
        if (!state.hasText(AfterSalesAgentState.USER_ID)) {
            return route(NEED_USER_INPUT, "USER_ID_REQUIRED");
        }
        if (state.hasText(AfterSalesAgentState.ORDER_STATUS)) {
            return route(EVALUATE_POLICY, "ORDER_ALREADY_LOADED");
        }
        if (!state.hasText(AfterSalesAgentState.ORDER_ID)
                && !state.hasText(AfterSalesAgentState.USER_MESSAGE)) {
            return route(NEED_USER_INPUT, "ORDER_ID_REQUIRED");
        }
        return route(DECIDE_TOOL, "ORDER_QUERY_REQUIRED");
    }

    private Map<String, Object> decideTool(AfterSalesAgentState state) {
        try {
            AfterSalesToolRequest request = toolPort.proposeOrderQuery(
                    state.text(AfterSalesAgentState.USER_MESSAGE),
                    state.text(AfterSalesAgentState.USER_ID),
                    state.text(AfterSalesAgentState.ORDER_ID),
                    state.text(AfterSalesAgentState.REFUND_REASON),
                    state.text(AfterSalesAgentState.DECISION_REASON)
            );
            Map<String, Object> update = new LinkedHashMap<>();
            update.put(AfterSalesAgentState.STAGE, AfterSalesStage.DECIDE_TOOL.name());
            update.put(AfterSalesAgentState.TOOL_CALL_ID, request.callId());
            update.put(AfterSalesAgentState.TOOL_NAME, request.toolName());
            update.put(AfterSalesAgentState.TOOL_ARGUMENTS, request.argumentsJson());
            update.put(AfterSalesAgentState.ROUTE, VALIDATE_TOOL);
            return update;
        } catch (IllegalArgumentException e) {
            return route(NEED_USER_INPUT, e.getMessage());
        } catch (Exception e) {
            return failureUpdate(state, "TEMPORARY_UNAVAILABLE", e.getMessage());
        }
    }

    private Map<String, Object> validateTool(AfterSalesAgentState state) {
        AfterSalesToolContractValidator.ValidationResult validation = contractValidator.validate(
                new AfterSalesToolRequest(
                        state.text(AfterSalesAgentState.TOOL_CALL_ID),
                        state.text(AfterSalesAgentState.TOOL_NAME),
                        state.text(AfterSalesAgentState.TOOL_ARGUMENTS)
                ),
                state.text(AfterSalesAgentState.ORDER_ID)
        );
        if (!validation.valid()) {
            return failureUpdate(state, validation.errorType(), validation.message());
        }
        return Map.of(
                AfterSalesAgentState.STAGE, AfterSalesStage.VALIDATE_TOOL.name(),
                AfterSalesAgentState.ORDER_ID, validation.orderId(),
                AfterSalesAgentState.ROUTE, EXECUTE_TOOL
        );
    }

    private Map<String, Object> executeTool(AfterSalesAgentState state) {
        AfterSalesToolResult result = toolPort.executeOrderQuery(
                new AfterSalesToolRequest(
                        state.text(AfterSalesAgentState.TOOL_CALL_ID),
                        state.text(AfterSalesAgentState.TOOL_NAME),
                        state.text(AfterSalesAgentState.TOOL_ARGUMENTS)
                ),
                state.text(AfterSalesAgentState.USER_ID),
                state.text(AfterSalesAgentState.USER_MESSAGE)
        );
        if (!result.success() || result.order() == null) {
            return failureUpdate(state, result.errorType(), result.errorMessage());
        }
        AfterSalesOrderSnapshot order = result.order();
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(AfterSalesAgentState.STAGE, AfterSalesStage.EXECUTE_TOOL.name());
        update.put(AfterSalesAgentState.TOOL_OUTPUT, result.outputJson());
        update.put(AfterSalesAgentState.ORDER_ID, order.orderId());
        update.put(AfterSalesAgentState.ORDER_OWNER_ID, order.ownerId());
        update.put(AfterSalesAgentState.ORDER_STATUS, order.status());
        if (order.daysSinceDelivery() != null) {
            update.put(AfterSalesAgentState.DAYS_SINCE_DELIVERY, order.daysSinceDelivery());
        }
        update.put(AfterSalesAgentState.ROUTE, EVALUATE_POLICY);
        update.put(AfterSalesAgentState.ERROR_TYPE, "");
        return update;
    }

    private Map<String, Object> evaluatePolicy(AfterSalesAgentState state) {
        RefundEligibilityPolicy.RefundDecision decision = eligibilityPolicy.evaluate(
                new RefundEligibilityPolicy.RefundRequest(
                        state.text(AfterSalesAgentState.USER_ID),
                        state.text(AfterSalesAgentState.ORDER_ID),
                        state.text(AfterSalesAgentState.ORDER_OWNER_ID),
                        state.text(AfterSalesAgentState.ORDER_STATUS),
                        state.text(AfterSalesAgentState.REFUND_REASON),
                        state.nullableInteger(AfterSalesAgentState.DAYS_SINCE_DELIVERY)
                )
        );
        String next = switch (decision.outcome()) {
            case NEED_USER_INPUT -> NEED_USER_INPUT;
            case ELIGIBLE -> READY_FOR_APPROVAL;
            case ALREADY_COMPLETED -> COMPLETED;
            case REJECTED -> REJECTED;
        };
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(AfterSalesAgentState.STAGE, AfterSalesStage.EVALUATE_POLICY.name());
        update.put(AfterSalesAgentState.ROUTE, next);
        update.put(AfterSalesAgentState.ELIGIBLE,
                decision.outcome() == RefundEligibilityPolicy.RefundOutcome.ELIGIBLE);
        update.put(AfterSalesAgentState.DECISION_REASON, decision.reason());
        if (REJECTED.equals(next) || COMPLETED.equals(next)) {
            update.put(AfterSalesAgentState.TERMINAL_REASON, decision.reason());
        }
        return update;
    }

    private Map<String, Object> repairToolInput(AfterSalesAgentState state) {
        boolean runnable = state.hasText(AfterSalesAgentState.USER_MESSAGE);
        return Map.of(
                AfterSalesAgentState.STAGE, AfterSalesStage.REPAIR_TOOL_INPUT.name(),
                AfterSalesAgentState.REPAIR_COUNT, state.count(AfterSalesAgentState.REPAIR_COUNT) + 1,
                AfterSalesAgentState.ROUTE, runnable ? DECIDE_TOOL : END
        );
    }

    private Map<String, Object> retryTool(AfterSalesAgentState state) {
        boolean runnable = state.hasText(AfterSalesAgentState.TOOL_NAME);
        return Map.of(
                AfterSalesAgentState.STAGE, AfterSalesStage.RETRY_TOOL.name(),
                AfterSalesAgentState.RETRY_COUNT, state.count(AfterSalesAgentState.RETRY_COUNT) + 1,
                AfterSalesAgentState.ROUTE, runnable ? EXECUTE_TOOL : END
        );
    }

    private Map<String, Object> reloadToolState(AfterSalesAgentState state) {
        boolean runnable = state.hasText(AfterSalesAgentState.TOOL_NAME);
        return Map.of(
                AfterSalesAgentState.STAGE, AfterSalesStage.RELOAD_TOOL_STATE.name(),
                AfterSalesAgentState.RELOAD_COUNT, state.count(AfterSalesAgentState.RELOAD_COUNT) + 1,
                AfterSalesAgentState.ROUTE, runnable ? EXECUTE_TOOL : END
        );
    }

    private Map<String, Object> executeRefund(AfterSalesAgentState state) {
        if (!"APPROVE".equalsIgnoreCase(state.text(AfterSalesAgentState.APPROVAL_DECISION))) {
            return terminal(REJECTED, "APPROVAL_REJECTED");
        }
        String caseId = state.text(AfterSalesAgentState.CASE_ID);
        String idempotencyKey = caseId + ":REFUND";
        AfterSalesRefundResult result = repository.executeRefund(
                caseId,
                state.text(AfterSalesAgentState.ORDER_ID),
                state.text(AfterSalesAgentState.USER_ID),
                idempotencyKey
        );
        if (!result.success()) {
            return terminal(REJECTED, result.reason());
        }
        return Map.of(
                AfterSalesAgentState.STAGE, AfterSalesStage.EXECUTE_REFUND.name(),
                AfterSalesAgentState.COMMAND_ID, result.commandId(),
                AfterSalesAgentState.DECISION_REASON,
                result.idempotentReplay() ? "IDEMPOTENT_REFUND_REPLAY" : "REFUND_EXECUTED",
                AfterSalesAgentState.ROUTE, VERIFY
        );
    }

    private Map<String, Object> verifyRefund(AfterSalesAgentState state) {
        Optional<AfterSalesOrderSnapshot> order = repository.findOrder(state.text(AfterSalesAgentState.ORDER_ID));
        if (order.isPresent() && "REFUNDED".equalsIgnoreCase(order.get().status())) {
            return terminal(COMPLETED, "REFUND_VERIFIED");
        }
        return terminal(REJECTED, "REFUND_VERIFICATION_FAILED");
    }

    private Map<String, Object> failureUpdate(AfterSalesAgentState state, String rawErrorType, String message) {
        ToolErrorType errorType = errorClassifier.classify(rawErrorType);
        String fingerprint = fingerprint(state.text(AfterSalesAgentState.TOOL_NAME),
                state.text(AfterSalesAgentState.TOOL_ARGUMENTS), errorType.name());
        boolean repeated = fingerprint.equals(state.text(AfterSalesAgentState.LAST_FAILURE_FINGERPRINT));
        Map<String, Object> seeded = new LinkedHashMap<>(state.data());
        seeded.put(AfterSalesAgentState.SAME_FAILURE_REPEATED, repeated);
        AfterSalesAgentState updatedState = new AfterSalesAgentState(seeded);
        Map<String, Object> update = new LinkedHashMap<>(recoveryUpdate(updatedState, errorType, message));
        update.put(AfterSalesAgentState.ERROR_TYPE, errorType.name());
        update.put(AfterSalesAgentState.LAST_FAILURE_FINGERPRINT, fingerprint);
        return update;
    }

    private Map<String, Object> recoveryUpdate(AfterSalesAgentState state,
                                               ToolErrorType errorType,
                                               String message) {
        ToolRecoveryPolicy.ToolRecoveryDecision decision = recoveryPolicy.decide(
                errorType,
                state.count(AfterSalesAgentState.REPAIR_COUNT),
                state.count(AfterSalesAgentState.RETRY_COUNT),
                state.count(AfterSalesAgentState.RELOAD_COUNT),
                state.flag(AfterSalesAgentState.SAME_FAILURE_REPEATED)
        );
        String next = switch (decision.action()) {
            case REPAIR -> REPAIR_TOOL_INPUT;
            case RETRY -> RETRY_TOOL;
            case RELOAD -> RELOAD_TOOL_STATE;
            case STOP -> REJECTED;
        };
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(AfterSalesAgentState.ROUTE, next);
        update.put(AfterSalesAgentState.DECISION_REASON,
                message == null || message.isBlank() ? decision.reason() : decision.reason() + ":" + message);
        if (REJECTED.equals(next)) {
            update.put(AfterSalesAgentState.TERMINAL_REASON, decision.reason());
        }
        return update;
    }

    private Map<String, Object> route(String target, String reason) {
        return Map.of(
                AfterSalesAgentState.ROUTE, target,
                AfterSalesAgentState.DECISION_REASON, reason
        );
    }

    private Map<String, Object> terminal(String target, String reason) {
        return Map.of(
                AfterSalesAgentState.ROUTE, target,
                AfterSalesAgentState.TERMINAL_REASON, reason
        );
    }

    private Map<String, Object> stage(AfterSalesStage stage) {
        return Map.of(AfterSalesAgentState.STAGE, stage.name());
    }

    private String fingerprint(String toolName, String arguments, String errorType) {
        try {
            String value = String.valueOf(toolName) + '|' + String.valueOf(arguments) + '|' + errorType;
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot calculate tool failure fingerprint", e);
        }
    }

    private static IAfterSalesToolPort unavailableToolPort() {
        return new IAfterSalesToolPort() {
            @Override
            public AfterSalesToolRequest proposeOrderQuery(String userMessage, String userId, String orderIdHint,
                                                           String refundReason, String correction) {
                throw new IllegalStateException("After-sales tool port is not configured");
            }

            @Override
            public AfterSalesToolResult executeOrderQuery(AfterSalesToolRequest request,
                                                          String userId,
                                                          String userMessage) {
                throw new IllegalStateException("After-sales tool port is not configured");
            }
        };
    }

    private static IAfterSalesRepository unavailableRepository() {
        return new IAfterSalesRepository() {
            @Override
            public Optional<AfterSalesOrderSnapshot> findOrder(String orderId) {
                return Optional.empty();
            }

            @Override
            public void createCase(String runId, String caseId, String userId, String sessionId, String message) {
            }

            @Override
            public void updateCase(AfterSalesCaseView caseView) {
            }

            @Override
            public Optional<AfterSalesCaseView> findCase(String runId) {
                return Optional.empty();
            }

            @Override
            public boolean cancelCase(String runId, String reason) {
                return false;
            }

            @Override
            public AfterSalesRefundResult executeRefund(String caseId, String orderId,
                                                        String userId, String idempotencyKey) {
                throw new IllegalStateException("After-sales repository is not configured");
            }
        };
    }
}
