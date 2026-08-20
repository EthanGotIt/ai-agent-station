package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private AgentWorkflowRunModel run(AgentWorkflowStatusEnum status, long version) {
        return new AgentWorkflowRunModel(
                "run-1", "thread-1", "turn-1", "user-1", AgentWorkflowTypeEnum.REFUND,
                status, version, NOW, NOW);
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
