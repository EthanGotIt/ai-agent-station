package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.workflow.AgentWorkflowTaskModel;
import cn.ethan.core.agent.workflow.AgentWorkflowTaskStore;
import cn.ethan.core.agent.workflow.AgentWorkflowOrchestrationVersionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证 WorkflowTask 持久化更新带有版本和不可变终态条件。
 *
 * @author ethan
 * @date 2026-08-21
 */
class MybatisAgentWorkflowTaskStoreVersionTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void updateUsesPreviousVersionAndExcludesImmutableTerminalRows() {
        AtomicReference<UpdateWrapper<AgentWorkflowTaskEntity>> captured = new AtomicReference<>();
        AgentWorkflowTaskMapper mapper = mapper((method, arguments) -> {
            if (method.equals("update")) {
                captured.set((UpdateWrapper<AgentWorkflowTaskEntity>) arguments[1]);
                return 1;
            }
            return defaultValue(method);
        });
        AgentWorkflowTaskStore store = new MybatisAgentWorkflowTaskStore(mapper);
        AgentWorkflowTaskModel next = run(AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, 1);

        store.update(next);

        UpdateWrapper<AgentWorkflowTaskEntity> wrapper = captured.get();
        String sql = wrapper.getExpression().getSqlSegment();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(0L),
                wrapper.getParamNameValuePairs()::toString);
        assertTrue(sql.contains("VERSION_NO"));
        assertTrue(sql.contains("ORCHESTRATION_VERSION"));
        assertTrue(sql.contains("STATUS"));
    }

    @Test
    void versionConflictIsReportedWhenNoRunWasUpdated() {
        AgentWorkflowTaskMapper mapper = mapper((method, arguments) ->
                method.equals("update") ? 0 : defaultValue(method));
        AgentWorkflowTaskStore store = new MybatisAgentWorkflowTaskStore(mapper);

        assertThrows(IllegalStateException.class,
                () -> store.update(run(AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, 1)));
    }

    @Test
    void sourceLookupMapsPersistedOrchestrationVersion() {
        AgentWorkflowTaskEntity entity = entity("EXPEDITE_GRAPH_V1");
        AgentWorkflowTaskMapper mapper = mapper((method, arguments) ->
                method.equals("selectBySource") ? entity : defaultValue(method));
        AgentWorkflowTaskModel found = new MybatisAgentWorkflowTaskStore(mapper)
                .findBySource("user-1", "turn-1", AgentWorkflowTypeEnum.ORDER_SERVICE)
                .orElseThrow();

        assertEquals(AgentWorkflowOrchestrationVersionEnum.EXPEDITE_GRAPH_V1,
                found.orchestrationVersion());
        assertEquals("run-1", found.taskId());
    }

    @Test
    void taskEntityKeepsTheLegacyTableAndIdentifierColumn() throws NoSuchFieldException {
        assertEquals("AGENT_WORKFLOW_RUN", AgentWorkflowTaskEntity.class.getAnnotation(TableName.class).value());
        assertEquals("RUN_ID", AgentWorkflowTaskEntity.class.getDeclaredField("taskId")
                .getAnnotation(TableId.class).value());
    }

    @Test
    void customTaskQueriesAliasTheLegacyRunIdColumn() {
        for (var method : AgentWorkflowTaskMapper.class.getDeclaredMethods()) {
            Select query = method.getAnnotation(Select.class);
            assertTrue(query != null && String.join(" ", query.value()).contains("RUN_ID AS TASK_ID"),
                    method.getName());
        }
    }

    @Test
    void unknownPersistedOrchestrationVersionFailsClosed() {
        AgentWorkflowTaskEntity entity = entity("UNKNOWN_V9");
        AgentWorkflowTaskMapper mapper = mapper((method, arguments) ->
                method.equals("selectBySource") ? entity : defaultValue(method));

        AgentThreadConflictException failure = assertThrows(AgentThreadConflictException.class,
                () -> new MybatisAgentWorkflowTaskStore(mapper)
                        .findBySource("user-1", "turn-1", AgentWorkflowTypeEnum.ORDER_SERVICE));
        assertEquals("UNKNOWN_WORKFLOW_ORCHESTRATION_VERSION", failure.code());
    }

    private AgentWorkflowTaskModel run(AgentWorkflowStatusEnum status, long version) {
        return new AgentWorkflowTaskModel(
                "run-1", "thread-1", "turn-1", "user-1", AgentWorkflowTypeEnum.REFUND,
                status, version, NOW, NOW);
    }

    private AgentWorkflowTaskEntity entity(String orchestrationVersion) {
        AgentWorkflowTaskEntity entity = new AgentWorkflowTaskEntity();
        entity.setTaskId("run-1");
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
    private AgentWorkflowTaskMapper mapper(Invocation invocation) {
        return (AgentWorkflowTaskMapper) Proxy.newProxyInstance(
                AgentWorkflowTaskMapper.class.getClassLoader(),
                new Class<?>[]{AgentWorkflowTaskMapper.class},
                (proxy, method, arguments) -> invocation.invoke(method.getName(), arguments));
    }

    private static Object defaultValue(String method) {
        if (method.equals("toString")) return "WorkflowTaskMapperTestProxy";
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] arguments);
    }
}
