package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowOrchestrationVersionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证 WorkflowRun 持久化更新带有版本和不可变终态条件。
 *
 * @author ethan
 * @date 2026-08-21
 */
class MybatisAgentWorkflowRunStoreVersionTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void updateUsesPreviousVersionAndExcludesImmutableTerminalRows() {
        AtomicReference<UpdateWrapper<AgentWorkflowRunEntity>> captured = new AtomicReference<>();
        AgentWorkflowRunMapper mapper = mapper((method, arguments) -> {
            if (method.equals("update")) {
                captured.set((UpdateWrapper<AgentWorkflowRunEntity>) arguments[1]);
                return 1;
            }
            return defaultValue(method);
        });
        AgentWorkflowRunStore store = new MybatisAgentWorkflowRunStore(mapper);
        AgentWorkflowRunModel next = run(AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, 1);

        store.update(next);

        UpdateWrapper<AgentWorkflowRunEntity> wrapper = captured.get();
        String sql = wrapper.getExpression().getSqlSegment();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(0L),
                wrapper.getParamNameValuePairs()::toString);
        assertTrue(sql.contains("VERSION_NO"));
        assertTrue(sql.contains("ORCHESTRATION_VERSION"));
        assertTrue(sql.contains("STATUS"));
    }

    @Test
    void versionConflictIsReportedWhenNoRunWasUpdated() {
        AgentWorkflowRunMapper mapper = mapper((method, arguments) ->
                method.equals("update") ? 0 : defaultValue(method));
        AgentWorkflowRunStore store = new MybatisAgentWorkflowRunStore(mapper);

        assertThrows(IllegalStateException.class,
                () -> store.update(run(AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, 1)));
    }

    @Test
    void sourceLookupMapsPersistedOrchestrationVersion() {
        AgentWorkflowRunEntity entity = entity("EXPEDITE_GRAPH_V1");
        AgentWorkflowRunMapper mapper = mapper((method, arguments) ->
                method.equals("selectBySource") ? entity : defaultValue(method));
        AgentWorkflowRunModel found = new MybatisAgentWorkflowRunStore(mapper)
                .findBySource("user-1", "turn-1", AgentWorkflowTypeEnum.ORDER_SERVICE)
                .orElseThrow();

        assertEquals(AgentWorkflowOrchestrationVersionEnum.EXPEDITE_GRAPH_V1,
                found.orchestrationVersion());
    }

    @Test
    void unknownPersistedOrchestrationVersionFailsClosed() {
        AgentWorkflowRunEntity entity = entity("UNKNOWN_V9");
        AgentWorkflowRunMapper mapper = mapper((method, arguments) ->
                method.equals("selectBySource") ? entity : defaultValue(method));

        AgentThreadConflictException failure = assertThrows(AgentThreadConflictException.class,
                () -> new MybatisAgentWorkflowRunStore(mapper)
                        .findBySource("user-1", "turn-1", AgentWorkflowTypeEnum.ORDER_SERVICE));
        assertEquals("UNKNOWN_WORKFLOW_ORCHESTRATION_VERSION", failure.code());
    }

    private AgentWorkflowRunModel run(AgentWorkflowStatusEnum status, long version) {
        return new AgentWorkflowRunModel(
                "run-1", "thread-1", "turn-1", "user-1", AgentWorkflowTypeEnum.REFUND,
                status, version, NOW, NOW);
    }

    private AgentWorkflowRunEntity entity(String orchestrationVersion) {
        AgentWorkflowRunEntity entity = new AgentWorkflowRunEntity();
        entity.setRunId("run-1");
        entity.setThreadId("thread-1");
        entity.setTurnId("turn-1");
        entity.setUserId("user-1");
        entity.setWorkflowType(AgentWorkflowTypeEnum.ORDER_SERVICE.name());
        entity.setOrchestrationVersion(orchestrationVersion);
        entity.setStatus(AgentWorkflowStatusEnum.WAITING_USER_INPUT.name());
        entity.setVersionNo(0L);
        entity.setStepsJson("[]");
        entity.setStateJson("{\"intent\":\"EXPEDITE\",\"orderId\":\"ORDER-1\"}");
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);
        return entity;
    }

    @SuppressWarnings("unchecked")
    private AgentWorkflowRunMapper mapper(Invocation invocation) {
        return (AgentWorkflowRunMapper) Proxy.newProxyInstance(
                AgentWorkflowRunMapper.class.getClassLoader(),
                new Class<?>[]{AgentWorkflowRunMapper.class},
                (proxy, method, arguments) -> invocation.invoke(method.getName(), arguments));
    }

    private static Object defaultValue(String method) {
        if (method.equals("toString")) return "WorkflowRunMapperTestProxy";
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] arguments);
    }
}
