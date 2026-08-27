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
 * 类型职责：构造异常订单 Workflow 的 LangGraph 技术骨架。
 *
 * <p>阶段一只验证图拓扑和技术快照接入；节点内的业务不变量在 Workflow 迁移阶段接入。</p>
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

    private static final List<String> NODES = List.of(
            RESOLVE_ORDER, VERIFY_FACTS, SWITCH_REQUIREMENTS, AUTHORIZE,
            EXECUTE_ACTION, VERIFY_OUTCOME, HANDOFF_AGENT);

    private final ObjectMapper objectMapper;

    public LangGraphWorkflowGraphFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CompiledGraph<AgentState> create(BaseCheckpointSaver saver) throws GraphStateException {
        return create(saver, 32);
    }

    public CompiledGraph<AgentState> create(BaseCheckpointSaver saver, int recursionLimit)
            throws GraphStateException {
        if (recursionLimit < 1) {
            throw new IllegalArgumentException("LangGraph recursionLimit 必须为正数");
        }
        StateGraph<AgentState> graph = new StateGraph<>(new Jackson3AgentGraphStateSerializer(objectMapper));
        for (String node : NODES) {
            graph.addNode(node, AsyncNodeAction.node_async(action(node)));
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
        if (saver != null) {
            config.checkpointSaver(saver);
        }
        return graph.compile(config.build());
    }

    public List<String> nodeNames() {
        return NODES;
    }

    private NodeAction<AgentState> action(String node) {
        return state -> Map.of("lastNode", node);
    }

    private AsyncEdgeAction<AgentState> factsEdge() {
        return state -> CompletableFuture.completedFuture(
                "RETRY".equals(state.value("factsDecision").orElse("READY")) ? "RETRY" : "READY");
    }
}
