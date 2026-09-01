package cn.ethan.infrastructure.agent.action.persistence;

import cn.ethan.core.agent.action.ExternalActionResultModel;
import cn.ethan.core.agent.action.ExternalActionResultStore;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 类型职责：用数据库唯一约束保存外部动作结果，承接 Worker 崩溃后的幂等恢复。
 * 该适配器需要保留可代理性，以承接 Spring 的异常翻译和事务边界。
 *
 * @author ethan
 * @date 2026-08-20
 */
@Repository
public class MybatisExternalActionResultStore implements ExternalActionResultStore {
    private final ExternalActionResultMapper mapper;

    public MybatisExternalActionResultStore(ExternalActionResultMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ExternalActionResultModel> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(mapper.selectByIdempotencyKey(idempotencyKey)).map(this::toModel);
    }

    @Override
    @Transactional
    public ExternalActionResultModel createIfAbsent(ExternalActionResultModel result) {
        ExternalActionResultEntity existing = mapper.selectByIdempotencyKey(result.idempotencyKey());
        if (existing != null) return toModel(existing);
        try {
            mapper.insert(toEntity(result));
            return result;
        } catch (DuplicateKeyException duplicate) {
            return Optional.ofNullable(mapper.selectByIdempotencyKey(result.idempotencyKey()))
                    .map(this::toModel).orElseThrow(() -> duplicate);
        }
    }

    private ExternalActionResultEntity toEntity(ExternalActionResultModel model) {
        ExternalActionResultEntity entity = new ExternalActionResultEntity();
        entity.setResultId(model.resultId());
        entity.setCommandId(model.commandId());
        entity.setIdempotencyKey(model.idempotencyKey());
        entity.setActionType(model.type());
        entity.setStatus(model.status());
        entity.setResponseJson(model.responseJson());
        entity.setCreatedAt(model.createdAt());
        return entity;
    }

    private ExternalActionResultModel toModel(ExternalActionResultEntity entity) {
        return new ExternalActionResultModel(entity.getResultId(), entity.getCommandId(), entity.getIdempotencyKey(),
                entity.getActionType(), entity.getStatus(), entity.getResponseJson(), entity.getCreatedAt());
    }
}
