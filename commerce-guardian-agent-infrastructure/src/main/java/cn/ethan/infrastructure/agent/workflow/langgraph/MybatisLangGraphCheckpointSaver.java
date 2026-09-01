package cn.ethan.infrastructure.agent.workflow.langgraph;

import cn.ethan.infrastructure.agent.workflow.persistence.AgentGraphSnapshotEntity;
import cn.ethan.infrastructure.agent.workflow.persistence.AgentGraphSnapshotMapper;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 类型职责：把 LangGraph 技术 Checkpoint 接到项目 MyBatis 表，并保持业务事实源独立。
 *
 * <p>快照按 runId 作为 graph thread ID 存储；缺少业务版本或指纹时只记录可重建的
 * 保守值，恢复流程必须重新读取 WorkflowRun，而不能据此批准外部动作。</p>
 *
 * <p>事务由 Workflow Engine 的外层 {@code TransactionTemplate} 提供；此类不声明
 * Spring 事务代理，否则代理 LangGraph {@code AbstractCheckpointSaver} 会绕过其内部锁的初始化。</p>
 *
 * @author ethan
 * @date 2026-08-27
 */
@Component
public class MybatisLangGraphCheckpointSaver extends AbstractCheckpointSaver {

    private static final TypeReference<Map<String, Object>> STATE_TYPE = new TypeReference<>() { };
    private static final String WORKFLOW_VERSION = "workflowVersion";
    private static final String FACTS_FINGERPRINT = "factsFingerprint";

    private final AgentGraphSnapshotMapper mapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MybatisLangGraphCheckpointSaver(AgentGraphSnapshotMapper mapper, ObjectMapper objectMapper, Clock clock) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    @Override
    protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) {
        String graphThreadId = graphThreadId(config);
        LinkedList<Checkpoint> checkpoints = new LinkedList<>();
        for (AgentGraphSnapshotEntity entity : mapper.selectByGraphThreadId(graphThreadId)) {
            checkpoints.add(toCheckpoint(entity));
        }
        return checkpoints;
    }

    @Override
    protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
                                       Checkpoint checkpoint) {
        save(config, checkpoint, null);
    }

    @Override
    protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
                                     Checkpoint checkpoint) {
        AgentGraphSnapshotEntity existing = mapper.selectByGraphThreadAndCheckpoint(
                graphThreadId(config), checkpoint.getId());
        save(config, checkpoint, existing);
    }

    @Override
    protected BaseCheckpointSaver.Tag releaseCheckpoints(RunnableConfig config,
                                                          LinkedList<Checkpoint> checkpoints) {
        // 技术快照要能支撑进程重启恢复；release 只通知 LangGraph，不删除业务可重建证据。
        return new BaseCheckpointSaver.Tag(graphThreadId(config), List.copyOf(checkpoints));
    }

    private void save(RunnableConfig config, Checkpoint checkpoint, AgentGraphSnapshotEntity existing) {
        String graphThreadId = graphThreadId(config);
        String stateJson = encodeState(checkpoint.getState());
        Map<String, Object> state = checkpoint.getState();
        Instant now = clock.instant();
        AgentGraphSnapshotEntity entity = existing == null ? new AgentGraphSnapshotEntity() : existing;
        if (entity.getSnapshotId() == null) {
            entity.setSnapshotId(UUID.randomUUID().toString());
            entity.setCreatedAt(now);
        }
        entity.setRunId(graphThreadId);
        entity.setGraphThreadId(graphThreadId);
        entity.setCheckpointId(checkpoint.getId());
        entity.setNodeId(Objects.requireNonNull(checkpoint.getNodeId(), "checkpoint nodeId cannot be null"));
        entity.setNextNodeId(checkpoint.getNextNodeId());
        entity.setStateJson(stateJson);
        entity.setWorkflowVersion(workflowVersion(config, state));
        entity.setFactsFingerprint(factsFingerprint(state, stateJson));
        entity.setUpdatedAt(now);
        if (existing == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
    }

    private Checkpoint toCheckpoint(AgentGraphSnapshotEntity entity) {
        try {
            Map<String, Object> state = objectMapper.readValue(entity.getStateJson(), STATE_TYPE);
            return Checkpoint.builder()
                    .id(entity.getCheckpointId())
                    .state(state)
                    .nodeId(entity.getNodeId())
                    .nextNodeId(entity.getNextNodeId())
                    .build();
        } catch (Exception failure) {
            throw new IllegalStateException("无法恢复 LangGraph 技术快照", failure);
        }
    }

    private String encodeState(Map<String, Object> state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码 LangGraph 技术状态", failure);
        }
    }

    private long workflowVersion(RunnableConfig config, Map<String, Object> state) {
        Object value = config.metadata(WORKFLOW_VERSION).orElse(state.get(WORKFLOW_VERSION));
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException failure) {
                // 版本不可信时回退 0，业务恢复会重新读取 WorkflowRun。
                return 0L;
            }
        }
        return 0L;
    }

    private String factsFingerprint(Map<String, Object> state, String stateJson) {
        Object value = state.get(FACTS_FINGERPRINT);
        if (value != null && !value.toString().isBlank()) {
            return value.toString();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(stateJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缺少 SHA-256", failure);
        }
    }

    private String graphThreadId(RunnableConfig config) {
        return config.threadId().filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("LangGraph 必须使用 WorkflowRun runId 作为 threadId"));
    }
}
