package cn.ethan.infrastructure.agent.workflow.langgraph;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.state.AgentState;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 类型职责：构造订单 Workflow 的 LangGraph 节点顺序和恢复边界。
 *
 * <p>业务授权事实由 Workflow 引擎在节点边界持久化；图本身只保存节点位置，
 * 不把技术 END 投影成业务成功。</p>
 *
 * @author ethan
 * @date 2026-08-27
 */
public final class LangGraphWorkflowGraphFactory {

    public static final String RESOLVE_ORDER = "RESOLVE_ORDER";
    public static final String VERIFY_FACTS = "VERIFY_FACTS";
    public static final String SWITCH_REQUIREMENTS = "SWITCH_REQUIREMENTS";
    public static final String AUTHORIZE = "AUTHORIZE";
    public static final String EXECUTE_ACTION = "EXECUTE_ACTION";
    public static final String VERIFY_OUTCOME = "VERIFY_OUTCOME";
    public static final String HANDOFF_AGENT = "HANDOFF_AGENT";
    /** 图节点写入的受控业务阶段，供引擎恢复时解释技术状态。 */
    public static final String BUSINESS_PHASE = "businessPhase";

    private static final List<String> NODES = List.of(
            RESOLVE_ORDER, VERIFY_FACTS, SWITCH_REQUIREMENTS, AUTHORIZE,
            EXECUTE_ACTION, VERIFY_OUTCOME, HANDOFF_AGENT);

    /** 固定节点顺序供业务投影和验收使用；不会暴露可变图结构。 */
    public static final List<String> NODES_FOR_DOCUMENTATION = NODES;

    private final ObjectMapper objectMapper;

    public LangGraphWorkflowGraphFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CompiledGraph<AgentState> create(BaseCheckpointSaver saver) throws GraphStateException {
        return create(saver, 32);
    }

    public CompiledGraph<AgentState> create(BaseCheckpointSaver saver, int recursionLimit)
            throws GraphStateException {
        return compile(saver, recursionLimit, false, false);
    }

    /**
     * 创建订单 Workflow 的运行图。固定流程在 AUTHORIZE 前中断，
     * 让业务层先持久化 QuestionCard 或 Workflow Checkpoint，再决定是否恢复图。
     */
    public CompiledGraph<AgentState> createOrderWorkflow(BaseCheckpointSaver saver, int recursionLimit)
            throws GraphStateException {
        return compile(saver, recursionLimit, true, true);
    }

    public CompiledGraph<AgentState> createOrderWorkflow(BaseCheckpointSaver saver)
            throws GraphStateException {
        return createOrderWorkflow(saver, 32);
    }

    /**
     * 创建试点恢复图。它从持久化业务事实重新验证完整节点路径，不依赖进程内技术快照，
     * 且不会在 AUTHORIZE 前再次暂停。
     */
    public CompiledGraph<AgentState> createExpediteExecutionWorkflow(
            BaseCheckpointSaver saver, int recursionLimit
    ) throws GraphStateException {
        return compile(saver, recursionLimit, false, true);
    }

    private CompiledGraph<AgentState> compile(
            BaseCheckpointSaver saver,
            int recursionLimit,
            boolean interruptAfterRequirements,
            boolean enforceBusinessContract
    ) throws GraphStateException {
        if (recursionLimit < 1) {
            throw new IllegalArgumentException("LangGraph recursionLimit 必须为正数");
        }
        StateGraph<AgentState> graph = new StateGraph<>(new Jackson3AgentGraphStateSerializer(objectMapper));
        for (String node : NODES) {
            graph.addNode(node, AsyncNodeAction.node_async(action(node, enforceBusinessContract)));
        }
        graph.addEdge(GraphDefinition.START, RESOLVE_ORDER);
        graph.addConditionalEdges(VERIFY_FACTS, factsEdge(),
                Map.of("READY", SWITCH_REQUIREMENTS, "RETRY", VERIFY_FACTS));
        graph.addEdge(RESOLVE_ORDER, VERIFY_FACTS);
        graph.addEdge(SWITCH_REQUIREMENTS, AUTHORIZE);
        graph.addEdge(AUTHORIZE, EXECUTE_ACTION);
        graph.addEdge(EXECUTE_ACTION, VERIFY_OUTCOME);
        graph.addEdge(VERIFY_OUTCOME, HANDOFF_AGENT);
        graph.addEdge(HANDOFF_AGENT, GraphDefinition.END);

        CompileConfig.Builder config = CompileConfig.builder().recursionLimit(recursionLimit);
        if (interruptAfterRequirements) {
            // 确认节点的业务事实由引擎先落库，图在完整的资格核验单元后暂停。
            config.interruptAfter(SWITCH_REQUIREMENTS);
        }
        if (saver != null) {
            config.checkpointSaver(saver);
        }
        return graph.compile(config.build());
    }

    public List<String> nodeNames() {
        return NODES;
    }

    private NodeAction<AgentState> action(String node, boolean enforceBusinessContract) {
        return state -> {
            if (enforceBusinessContract
                    && "EXPEDITE_GRAPH_V1".equals(state.value("orchestrationVersion").orElse(""))) {
                validateBusinessInput(node, state);
            }
            Map<String, Object> next = new java.util.LinkedHashMap<>(state.data());
            next.put("lastNode", node);
            next.put(BUSINESS_PHASE, phase(node));
            return next;
        };
    }

    private void validateBusinessInput(String node, AgentState state) {
        switch (node) {
            case RESOLVE_ORDER -> {
                boolean hasOrder = state.value("orderId").map(this::hasValue).orElse(false);
                boolean hasCandidate = state.value("candidateOrderIds").map(this::hasValue).orElse(false);
                if (!hasOrder && !hasCandidate) {
                    throw new IllegalStateException("催发货图缺少订单事实");
                }
            }
            case VERIFY_FACTS -> {
                if (state.value("factsFingerprint").map(this::hasValue).orElse(false) == false) {
                    throw new IllegalStateException("催发货图缺少事实指纹");
                }
                if (!"READY".equals(state.value("factsDecision").orElse("READY"))) {
                    return;
                }
            }
            case SWITCH_REQUIREMENTS -> {
                if (state.value("selectedOrder").map(this::hasValue).orElse(false) == false) {
                    throw new IllegalStateException("催发货图缺少已选择订单");
                }
            }
            case AUTHORIZE, EXECUTE_ACTION, VERIFY_OUTCOME, HANDOFF_AGENT -> {
                // 这些节点的持久化授权和外部执行分别由引擎与 Worker 完成。
            }
            default -> throw new IllegalStateException("未知催发货图节点：" + node);
        }
    }

    private String phase(String node) {
        return switch (node) {
            case RESOLVE_ORDER -> "ORDER_READ";
            case VERIFY_FACTS -> "ELIGIBILITY_VERIFIED";
            case SWITCH_REQUIREMENTS -> "CONFIRMATION_READY";
            case AUTHORIZE -> "AUTHORIZATION_PENDING";
            case EXECUTE_ACTION -> "ACTION_COMMAND_CREATED";
            case VERIFY_OUTCOME -> "AWAITING_EXTERNAL_ACTION";
            case HANDOFF_AGENT -> "HANDOFF_TO_WORKER";
            default -> node;
        };
    }

    private boolean hasValue(Object value) {
        return value != null && !value.toString().isBlank()
                && !"[]".equals(value.toString()) && !"{}".equals(value.toString());
    }

    private AsyncEdgeAction<AgentState> factsEdge() {
        return state -> CompletableFuture.completedFuture(
                "RETRY".equals(state.value("factsDecision").orElse("READY")) ? "RETRY" : "READY");
    }
}
