package cn.ethan.ai.infrastructure.adapter.repository;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.Checkpoint;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.port.driven.ICheckpointRepository;
import cn.ethan.ai.infrastructure.dao.CheckpointMapper;
import cn.ethan.ai.infrastructure.dao.po.CheckpointPO;
import cn.ethan.ai.types.common.id.CaseId;
import cn.ethan.ai.types.common.id.CheckpointId;
import cn.ethan.ai.types.common.id.StepId;
import cn.ethan.ai.types.common.id.TurnId;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Checkpoint 仓储实现。
 */
@Repository
public class CheckpointRepository implements ICheckpointRepository {

    private final CheckpointMapper checkpointMapper;

    public CheckpointRepository(CheckpointMapper checkpointMapper) {
        this.checkpointMapper = checkpointMapper;
    }

    @Override
    public void save(Checkpoint checkpoint) {
        checkpointMapper.insert(toPO(checkpoint));
    }

    @Override
    public Optional<Checkpoint> findById(CheckpointId checkpointId) {
        return checkpointMapper.selectById(checkpointId.value()).map(this::toDomain);
    }

    private CheckpointPO toPO(Checkpoint checkpoint) {
        return CheckpointPO.builder()
                .checkpointId(checkpoint.checkpointId().value())
                .caseId(checkpoint.caseId().value())
                .turnId(checkpoint.turnId().value())
                .stepId(checkpoint.stepId() == null ? null : checkpoint.stepId().value())
                .ssmState(checkpoint.ssmState())
                .statePayload(JSON.toJSONString(checkpoint.state().data()))
                .stage(checkpoint.stage().name())
                .createdAt(checkpoint.createdAt())
                .build();
    }

    private Checkpoint toDomain(CheckpointPO po) {
        return new Checkpoint(
                CheckpointId.of(po.getCheckpointId()),
                CaseId.of(po.getCaseId()),
                TurnId.of(po.getTurnId()),
                po.getStepId() == null ? null : StepId.of(po.getStepId()),
                po.getSsmState(),
                new AfterSalesAgentState(parseStatePayload(po.getStatePayload())),
                parseStage(po.getStage()),
                po.getCreatedAt()
        );
    }

    private Map<String, Object> parseStatePayload(String payload) {
        return JSON.parseObject(payload, new TypeReference<>() {
        });
    }

    private AfterSalesStage parseStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return AfterSalesStage.INTAKE;
        }
        try {
            return AfterSalesStage.valueOf(stage.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return AfterSalesStage.INTAKE;
        }
    }
}
