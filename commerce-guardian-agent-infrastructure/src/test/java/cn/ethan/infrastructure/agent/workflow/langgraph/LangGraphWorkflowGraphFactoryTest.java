package cn.ethan.infrastructure.agent.workflow.langgraph;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 类型职责：验证 LangGraph 阶段一的固定拓扑、条件循环和最大迭代门禁。
 *
 * @author ethan
 * @date 2026-08-27
 */
class LangGraphWorkflowGraphFactoryTest {

    @Test
    void compilesFixedOrderTopologyAndRunsToEnd() throws Exception {
        LangGraphWorkflowGraphFactory factory = new LangGraphWorkflowGraphFactory(new ObjectMapper());
        CompiledGraph<AgentState> graph = factory.create(new MemorySaver());

        assertEquals(7, factory.nodeNames().size());
        assertEquals("HANDOFF_AGENT", graph.invoke(Map.of(
                "factsDecision", "READY"), RunnableConfig.builder().threadId("run-1").build())
                .orElseThrow().value("lastNode").orElseThrow());
    }

    @Test
    void conditionalLoopStopsAtConfiguredMaximumIteration() throws GraphStateException {
        LangGraphWorkflowGraphFactory factory = new LangGraphWorkflowGraphFactory(new ObjectMapper());
        CompiledGraph<AgentState> graph = factory.create(null, 2);

        assertThrows(RuntimeException.class, () -> graph.invoke(Map.of("factsDecision", "RETRY"),
                RunnableConfig.builder().threadId("run-loop").build()));
    }

    @Test
    void graphUsesTheProjectJackson3StateSerializer() throws GraphStateException {
        LangGraphWorkflowGraphFactory factory = new LangGraphWorkflowGraphFactory(new ObjectMapper());
        CompiledGraph<AgentState> graph = factory.create(null);

        assertNotNull(graph.stateGraph.getStateSerializer());
        assertEquals(Jackson3AgentGraphStateSerializer.class,
                graph.stateGraph.getStateSerializer().getClass());
        assertEquals(GraphDefinition.START, GraphDefinition.START);
    }
}
