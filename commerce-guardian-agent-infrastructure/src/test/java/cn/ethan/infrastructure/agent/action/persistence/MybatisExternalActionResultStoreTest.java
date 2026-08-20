package cn.ethan.infrastructure.agent.action.persistence;

import cn.ethan.core.agent.action.ExternalActionResultModel;
import cn.ethan.core.agent.action.ExternalActionResultStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证外部动作结果从持久化实体读取时保留动作类型，保障幂等重放。
 *
 * @author ethan
 * @date 2026-08-21
 */
class MybatisExternalActionResultStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void readsActionTypeForIdempotentReplay() {
        ExternalActionResultEntity entity = new ExternalActionResultEntity();
        entity.setResultId("result-1");
        entity.setCommandId("command-1");
        entity.setIdempotencyKey("idem-1");
        entity.setActionType(ExternalActionTypeEnum.REFUND);
        entity.setStatus(ExternalActionResultStatusEnum.SUCCEEDED);
        entity.setResponseJson("{\"status\":\"SUCCEEDED\"}");
        entity.setCreatedAt(NOW);

        ExternalActionResultMapper mapper = (ExternalActionResultMapper) Proxy.newProxyInstance(
                ExternalActionResultMapper.class.getClassLoader(),
                new Class<?>[]{ExternalActionResultMapper.class},
                (proxy, method, arguments) -> method.getName().equals("selectByIdempotencyKey")
                        ? entity : defaultValue(method.getReturnType()));

        MybatisExternalActionResultStore store = new MybatisExternalActionResultStore(mapper);

        var result = store.findByIdempotencyKey("idem-1");

        assertTrue(result.isPresent());
        ExternalActionResultModel model = result.orElseThrow();
        assertEquals(ExternalActionTypeEnum.REFUND, model.type());
        assertEquals(ExternalActionResultStatusEnum.SUCCEEDED, model.status());
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
