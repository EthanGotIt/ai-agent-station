package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentWorkflowAnswerInput;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 类型职责：只持久化 Turn 生命周期、请求幂等和重启恢复状态。
 *
 * @author ethan
 * @date 2026-08-20
 */
@Repository
public final class MybatisAgentTurnStore implements AgentTurnStore {

    private final AgentTurnMapper mapper;
    private final AgentItemMapper itemMapper;
    private final AgentThreadMapper threadMapper;
    private final JacksonAgentWorkflowAnswerCodec answerCodec;

    public MybatisAgentTurnStore(
            AgentTurnMapper mapper,
            AgentItemMapper itemMapper,
            AgentThreadMapper threadMapper,
            JacksonAgentWorkflowAnswerCodec answerCodec
    ) {
        this.mapper = mapper;
        this.itemMapper = itemMapper;
        this.threadMapper = threadMapper;
        this.answerCodec = answerCodec;
    }

    @Override
    public Optional<AgentTurnModel> findTurn(String userId, String turnId) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<AgentTurnEntity>()
                        .eq("TURN_ID", turnId).eq("USER_ID", userId)))
                .map(this::toModel);
    }

    @Override
    public Optional<AgentTurnModel> findTurnByRequest(String userId, String clientRequestId) {
        return Optional.ofNullable(mapper.selectByRequest(userId, clientRequestId)).map(this::toModel);
    }

    @Override
    public Optional<AgentTurnModel> findTurnByRequestForUpdate(String userId, String clientRequestId) {
        return Optional.ofNullable(mapper.selectByRequestForUpdate(userId, clientRequestId)).map(this::toModel);
    }

    @Override
    public void createTurn(AgentTurnModel turn) {
        mapper.insert(toEntity(turn));
    }

    @Override
    @Transactional
    public long createTurnWithInitialItem(AgentTurnModel turn, AgentItemModel initialItem) {
        mapper.insert(toEntity(turn));
        AgentThreadEntity thread = threadMapper.selectForUpdate(turn.threadId());
        if (thread == null) {
            throw new IllegalStateException("Thread 不存在：" + turn.threadId());
        }
        long sequence = thread.getNextSequence() == null || thread.getNextSequence() < 1
                ? 1L : thread.getNextSequence();
        AgentItemEntity item = new AgentItemEntity();
        item.setItemId(initialItem.itemId());
        item.setThreadId(initialItem.threadId());
        item.setTurnId(initialItem.turnId());
        item.setSequenceNo(sequence);
        item.setItemType(initialItem.type().name());
        item.setPayloadJson(initialItem.payloadJson());
        item.setCreatedAt(initialItem.createdAt());
        itemMapper.insert(item);
        thread.setNextSequence(sequence + 1);
        thread.setUpdatedAt(initialItem.createdAt());
        threadMapper.updateById(thread);
        return sequence;
    }

    @Override
    public boolean updateTurn(AgentTurnModel expected, AgentTurnModel next) {
        if (!expected.turnId().equals(next.turnId()) || !expected.userId().equals(next.userId())) {
            throw new IllegalArgumentException("Turn CAS 的身份不一致");
        }
        if (next.version() != expected.version() + 1) {
            throw new IllegalArgumentException("Turn CAS 必须单调推进一个版本");
        }
        if (isTerminal(expected.status())) {
            return false;
        }
        AgentWorkflowAnswerInput answer = next.workflowAnswerInput();
        int updated = mapper.update(null, new UpdateWrapper<AgentTurnEntity>()
                .eq("TURN_ID", next.turnId())
                .eq("USER_ID", next.userId())
                .eq("VERSION_NO", expected.version())
                .set("STATUS", next.status().name())
                .set("QUEUE_POSITION", next.queuePosition())
                .set("WORKFLOW_RUN_ID", next.workflowRunId())
                .set("WORKFLOW_QUESTION_ID", answer == null ? null : answer.questionId())
                .set("WORKFLOW_CHECKPOINT_ID", answer == null ? null : answer.checkpointId())
                .set("WORKFLOW_QUESTION_VERSION", answer == null ? null : answer.enqueuedQuestionVersion())
                .set("WORKFLOW_ANSWERS_JSON", answer == null ? null : answerCodec.encodeAnswers(answer))
                .set("ERROR_CODE", next.errorCode())
                .set("STARTED_AT", next.startedAt())
                .set("FINISHED_AT", next.finishedAt())
                .set("VERSION_NO", next.version()));
        if (updated == 0) {
            return false;
        }
        if (updated != 1) {
            throw new IllegalStateException("Turn CAS 更新了多行：" + next.turnId());
        }
        return true;
    }

    @Override
    public List<AgentTurnModel> listRecoverableTurns() {
        return mapper.selectRecoverable().stream().map(this::toModel).toList();
    }

    @Override
    public List<AgentTurnModel> listWorkflowAnswerReconciliationCandidates() {
        return mapper.selectWorkflowAnswerReconciliationCandidates().stream().map(this::toModel).toList();
    }

    private AgentTurnEntity toEntity(AgentTurnModel model) {
        AgentTurnEntity entity = new AgentTurnEntity();
        entity.setTurnId(model.turnId());
        entity.setThreadId(model.threadId());
        entity.setUserId(model.userId());
        entity.setClientRequestId(model.clientRequestId());
        entity.setInputText(model.input());
        entity.setStatus(model.status().name());
        entity.setQueuePosition(model.queuePosition());
        entity.setWorkflowRunId(model.workflowRunId());
        AgentWorkflowAnswerInput answer = model.workflowAnswerInput();
        if (answer != null) {
            entity.setWorkflowQuestionId(answer.questionId());
            entity.setWorkflowCheckpointId(answer.checkpointId());
            entity.setWorkflowQuestionVersion(answer.enqueuedQuestionVersion());
            entity.setWorkflowAnswersJson(answerCodec.encodeAnswers(answer));
        }
        entity.setErrorCode(model.errorCode());
        entity.setCreatedAt(model.createdAt());
        entity.setStartedAt(model.startedAt());
        entity.setFinishedAt(model.finishedAt());
        entity.setVersionNo(model.version());
        return entity;
    }

    private AgentTurnModel toModel(AgentTurnEntity entity) {
        return new AgentTurnModel(entity.getTurnId(), entity.getThreadId(), entity.getUserId(),
                entity.getClientRequestId(), entity.getInputText(), AgentTurnStatusEnum.valueOf(entity.getStatus()),
                value(entity.getQueuePosition()), entity.getWorkflowRunId(), entity.getErrorCode(), entity.getCreatedAt(),
                entity.getStartedAt(), entity.getFinishedAt(), toAnswerInput(entity), value(entity.getVersionNo()));
    }

    private AgentWorkflowAnswerInput toAnswerInput(AgentTurnEntity entity) {
        if (entity.getWorkflowQuestionId() == null && entity.getWorkflowCheckpointId() == null
                && entity.getWorkflowQuestionVersion() == null && entity.getWorkflowAnswersJson() == null) {
            return null;
        }
        if (entity.getWorkflowRunId() == null || entity.getWorkflowQuestionId() == null
                || entity.getWorkflowCheckpointId() == null || entity.getWorkflowQuestionVersion() == null
                || entity.getWorkflowAnswersJson() == null) {
            throw new IllegalStateException("回答 Turn 的结构化持久字段不完整：" + entity.getTurnId());
        }
        return answerCodec.decode(entity.getWorkflowRunId(), entity.getWorkflowQuestionId(),
                entity.getWorkflowCheckpointId(), entity.getWorkflowQuestionVersion(),
                entity.getWorkflowAnswersJson());
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static boolean isTerminal(AgentTurnStatusEnum status) {
        return status == AgentTurnStatusEnum.COMPLETED
                || status == AgentTurnStatusEnum.CANCELLED
                || status == AgentTurnStatusEnum.TIMED_OUT
                || status == AgentTurnStatusEnum.FAILED;
    }
}
