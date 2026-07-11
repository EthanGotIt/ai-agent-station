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
import cn.ethan.ai.infrastructure.json.AfterSalesJsonCodec;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Checkpoint 仓储实现。
 */
@Repository
public class CheckpointRepository implements ICheckpointRepository {

    private final CheckpointMapper checkpointMapper;
    private final AfterSalesJsonCodec jsonCodec;

    public CheckpointRepository(CheckpointMapper checkpointMapper) {
        this(checkpointMapper, AfterSalesJsonCodec.defaultCodec());
    }

    @Autowired
    public CheckpointRepository(CheckpointMapper checkpointMapper, AfterSalesJsonCodec jsonCodec) {
        this.checkpointMapper = checkpointMapper;
        this.jsonCodec = jsonCodec;
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
                .statePayload(jsonCodec.write(checkpoint.state().data(), "序列化 checkpoint 状态"))
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
        return jsonCodec.read(payload, new TypeReference<>() {
        }, "解析 checkpoint 状态");
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
