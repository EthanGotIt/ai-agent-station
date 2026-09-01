package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentThreadModel;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thread 持久化测试：标题更新不能覆盖开放交互和其他运行时事实。
 *
 * @author ethan
 * @date 2026-08-28
 */
class MybatisAgentThreadStoreTest {

    @Test
    void titleUpdateDoesNotWriteRuntimeInteractionColumns() {
        CapturedUpdate captured = new CapturedUpdate();
        MybatisAgentThreadStore store = new MybatisAgentThreadStore(mapper(captured));
        AgentThreadModel thread = new AgentThreadModel(
                "thread-1", "user-1", "新标题", cn.ethan.core.agent.thread.AgentThreadStatusEnum.ACTIVE,
                null, null, 17, Instant.EPOCH, Instant.parse("2026-08-28T00:00:00Z"),
                null, cn.ethan.core.agent.thread.AgentInteractionTypeEnum.WORKFLOW_CHECKPOINT,
                "checkpoint-1");

        store.updateThread(thread);

        String sqlSet = captured.wrapper.getSqlSet();
        assertTrue(sqlSet.contains("TITLE"));
        assertTrue(sqlSet.contains("UPDATED_AT"));
        assertFalse(sqlSet.contains("OPEN_QUESTION_ID"));
        assertFalse(sqlSet.contains("OPEN_INTERACTION_TYPE"));
        assertFalse(sqlSet.contains("OPEN_INTERACTION_ID"));
        assertFalse(sqlSet.contains("NEXT_SEQUENCE"));
    }

    @SuppressWarnings("unchecked")
    private AgentThreadMapper mapper(CapturedUpdate captured) {
        return (AgentThreadMapper) Proxy.newProxyInstance(
                AgentThreadMapper.class.getClassLoader(), new Class<?>[]{AgentThreadMapper.class},
                (proxy, method, args) -> {
                    if ("update".equals(method.getName())) {
                        captured.wrapper = (UpdateWrapper<AgentThreadEntity>) args[1];
                        return 1;
                    }
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == List.class) return List.of();
                    return null;
                });
    }

    private static final class CapturedUpdate {
        private UpdateWrapper<AgentThreadEntity> wrapper;
    }
}
