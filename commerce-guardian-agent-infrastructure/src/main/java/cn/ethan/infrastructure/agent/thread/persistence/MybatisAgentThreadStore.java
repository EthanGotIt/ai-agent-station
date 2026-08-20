package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.context.AgentContextSnapshotModel;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.workflow.AgentQuestionModel;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentThreadStore;
import cn.ethan.infrastructure.agent.thread.persistence.AgentContextSnapshotEntity;
import cn.ethan.infrastructure.agent.thread.persistence.AgentItemEntity;
import cn.ethan.infrastructure.agent.thread.persistence.AgentQuestionEntity;
import cn.ethan.infrastructure.agent.thread.persistence.AgentThreadEntity;
import cn.ethan.infrastructure.agent.thread.persistence.AgentTurnEntity;
import cn.ethan.infrastructure.agent.thread.persistence.AgentContextSnapshotMapper;
import cn.ethan.infrastructure.agent.thread.persistence.AgentItemMapper;
import cn.ethan.infrastructure.agent.thread.persistence.AgentQuestionMapper;
import cn.ethan.infrastructure.agent.thread.persistence.AgentThreadMapper;
import cn.ethan.infrastructure.agent.thread.persistence.AgentTurnMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 类型职责：使用 MyBatis-Plus 持久化 Thread 事实，并集中处理边界转换。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Repository
public final class MybatisAgentThreadStore implements AgentThreadStore {

    private final AgentThreadMapper threadMapper;
    private final AgentTurnMapper turnMapper;
    private final AgentItemMapper itemMapper;
    private final AgentQuestionMapper questionMapper;
    private final AgentContextSnapshotMapper snapshotMapper;

    public MybatisAgentThreadStore(
            AgentThreadMapper threadMapper,
            AgentTurnMapper turnMapper,
            AgentItemMapper itemMapper,
            AgentQuestionMapper questionMapper,
            AgentContextSnapshotMapper snapshotMapper
    ) {
        this.threadMapper = threadMapper;
        this.turnMapper = turnMapper;
        this.itemMapper = itemMapper;
        this.questionMapper = questionMapper;
        this.snapshotMapper = snapshotMapper;
    }

    @Override
    public void createThread(AgentThreadModel thread) {
        threadMapper.insert(toEntity(thread));
    }

    @Override
    public Optional<AgentThreadModel> findThread(String userId, String threadId) {
        AgentThreadEntity entity = threadMapper.selectOne(new QueryWrapper<AgentThreadEntity>()
                .eq("THREAD_ID", threadId)
                .eq("USER_ID", userId));
        return Optional.ofNullable(entity).map(this::toModel);
    }

    @Override
    public List<AgentThreadModel> listThreads(String userId) {
        return threadMapper.selectByUser(userId).stream().map(this::toModel).toList();
    }

    @Override
    public void updateThread(AgentThreadModel thread) {
        threadMapper.update(toEntity(thread), new UpdateWrapper<AgentThreadEntity>()
                .eq("THREAD_ID", thread.threadId())
                .eq("USER_ID", thread.userId()));
    }

    @Override
    public Optional<AgentTurnModel> findTurnByRequest(String userId, String clientRequestId) {
        return Optional.ofNullable(turnMapper.selectByRequest(userId, clientRequestId)).map(this::toModel);
    }

    @Override
    public void createTurn(AgentTurnModel turn) {
        turnMapper.insert(toEntity(turn));
    }

    @Override
    public void updateTurn(AgentTurnModel turn) {
        turnMapper.update(toEntity(turn), new UpdateWrapper<AgentTurnEntity>()
                .eq("TURN_ID", turn.turnId())
                .eq("USER_ID", turn.userId()));
    }

    @Override
    public List<AgentTurnModel> listTurns(String userId, String threadId) {
        return turnMapper.selectByThread(userId, threadId).stream().map(this::toModel).toList();
    }

    @Override
    public List<AgentTurnModel> listRecoverableTurns() {
        return turnMapper.selectRecoverable().stream().map(this::toModel).toList();
    }

    @Override
    @Transactional
    public synchronized long appendItem(AgentItemModel item) {
        AgentThreadEntity thread = threadMapper.selectForUpdate(item.threadId());
        if (thread == null) {
            throw new IllegalStateException("Thread 不存在：" + item.threadId());
        }
        long sequence = thread.getNextSequence() == null ? 0L : thread.getNextSequence();
        AgentItemEntity entity = toEntity(item, sequence);
        itemMapper.insert(entity);
        thread.setNextSequence(sequence + 1);
        thread.setUpdatedAt(item.createdAt());
        threadMapper.updateById(thread);
        return sequence;
    }

    @Override
    public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
        if (findThread(userId, threadId).isEmpty()) {
            return List.of();
        }
        return itemMapper.selectAfter(threadId, Math.max(0L, afterSequence), Math.max(1, Math.min(limit, 500)))
                .stream().map(this::toModel).toList();
    }

    @Override
    public Optional<AgentQuestionModel> findOpenQuestion(String userId, String threadId) {
        return Optional.ofNullable(questionMapper.selectOpen(userId, threadId)).map(this::toModel);
    }

    @Override
    public Optional<AgentQuestionModel> findOpenQuestionByRun(String userId, String runId) {
        return Optional.ofNullable(questionMapper.selectOpenByRun(userId, runId)).map(this::toModel);
    }

    @Override
    @Transactional
    public synchronized void saveQuestion(AgentQuestionModel question) {
        if (!questionMapper.selectOpenForUpdate(question.threadId()).isEmpty()) {
            throw new IllegalStateException("同一 Thread 只能存在一个开放 QuestionCard");
        }
        questionMapper.insert(toEntity(question));
    }

    @Override
    @Transactional
    public void answerQuestion(AgentQuestionModel question) {
        long previousVersion = Math.max(0L, question.version() - 1);
        int updated = questionMapper.update(null, new UpdateWrapper<AgentQuestionEntity>()
                .eq("QUESTION_ID", question.questionId())
                .eq("STATUS", "OPEN")
                .eq("VERSION_NO", previousVersion)
                .set("VERSION_NO", question.version())
                .set("STATUS", question.status())
                .set("ANSWERED_AT", question.answeredAt()));
        if (updated != 1) {
            throw new IllegalStateException("QuestionCard 已被其他请求处理");
        }
    }

    @Override
    public Optional<AgentContextSnapshotModel> findLatestSnapshot(String userId, String threadId) {
        if (findThread(userId, threadId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshotMapper.selectLatest(threadId)).map(this::toModel);
    }

    @Override
    public void saveSnapshot(AgentContextSnapshotModel snapshot) {
        snapshotMapper.insert(toEntity(snapshot));
    }

    private AgentThreadEntity toEntity(AgentThreadModel model) {
        AgentThreadEntity entity = new AgentThreadEntity();
        entity.setThreadId(model.threadId());
        entity.setUserId(model.userId());
        entity.setTitle(model.title());
        entity.setStatus(model.status().name());
        entity.setContextType(model.contextType());
        entity.setContextId(model.contextId());
        entity.setNextSequence(model.nextSequence());
        entity.setCreatedAt(model.createdAt());
        entity.setUpdatedAt(model.updatedAt());
        return entity;
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
        entity.setErrorCode(model.errorCode());
        entity.setCreatedAt(model.createdAt());
        entity.setStartedAt(model.startedAt());
        entity.setFinishedAt(model.finishedAt());
        return entity;
    }

    private AgentItemEntity toEntity(AgentItemModel model, long sequence) {
        AgentItemEntity entity = new AgentItemEntity();
        entity.setItemId(model.itemId());
        entity.setThreadId(model.threadId());
        entity.setTurnId(model.turnId());
        entity.setSequenceNo(sequence);
        entity.setItemType(model.type().name());
        entity.setPayload(model.payload());
        entity.setCreatedAt(model.createdAt());
        return entity;
    }

    private AgentQuestionEntity toEntity(AgentQuestionModel model) {
        AgentQuestionEntity entity = new AgentQuestionEntity();
        entity.setQuestionId(model.questionId());
        entity.setRunId(model.runId());
        entity.setThreadId(model.threadId());
        entity.setTurnId(model.turnId());
        entity.setUserId(model.userId());
        entity.setCheckpointId(model.checkpointId());
        entity.setVersionNo(model.version());
        entity.setTitle(model.title());
        entity.setPrompt(model.prompt());
        entity.setFieldsJson(model.fieldsJson());
        entity.setStatus(model.status());
        entity.setCreatedAt(model.createdAt());
        entity.setAnsweredAt(model.answeredAt());
        return entity;
    }

    private AgentContextSnapshotEntity toEntity(AgentContextSnapshotModel model) {
        AgentContextSnapshotEntity entity = new AgentContextSnapshotEntity();
        entity.setSnapshotId(model.snapshotId());
        entity.setThreadId(model.threadId());
        entity.setThroughSequence(model.throughSequence());
        entity.setVersionNo(model.version());
        entity.setEstimatedTokens(model.estimatedTokens());
        entity.setSummary(model.summary());
        entity.setCreatedAt(model.createdAt());
        return entity;
    }

    private AgentThreadModel toModel(AgentThreadEntity entity) {
        return new AgentThreadModel(entity.getThreadId(), entity.getUserId(), entity.getTitle(),
                AgentThreadStatusEnum.valueOf(entity.getStatus()), entity.getContextType(), entity.getContextId(),
                value(entity.getNextSequence()), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private AgentTurnModel toModel(AgentTurnEntity entity) {
        return new AgentTurnModel(entity.getTurnId(), entity.getThreadId(), entity.getUserId(),
                entity.getClientRequestId(), entity.getInputText(), AgentTurnStatusEnum.valueOf(entity.getStatus()),
                value(entity.getQueuePosition()), entity.getWorkflowRunId(), entity.getErrorCode(), entity.getCreatedAt(),
                entity.getStartedAt(), entity.getFinishedAt());
    }

    private AgentItemModel toModel(AgentItemEntity entity) {
        return new AgentItemModel(entity.getItemId(), entity.getThreadId(), entity.getTurnId(),
                value(entity.getSequenceNo()), AgentItemTypeEnum.valueOf(entity.getItemType()), entity.getPayload(), entity.getCreatedAt());
    }

    private AgentQuestionModel toModel(AgentQuestionEntity entity) {
        return new AgentQuestionModel(entity.getRunId(), entity.getThreadId(), entity.getTurnId(), entity.getUserId(),
                entity.getQuestionId(), entity.getCheckpointId(), value(entity.getVersionNo()), entity.getTitle(),
                entity.getPrompt(), entity.getFieldsJson(), entity.getStatus(), entity.getCreatedAt(), entity.getAnsweredAt());
    }

    private AgentContextSnapshotModel toModel(AgentContextSnapshotEntity entity) {
        return new AgentContextSnapshotModel(entity.getSnapshotId(), entity.getThreadId(), value(entity.getThroughSequence()),
                value(entity.getVersionNo()), value(entity.getEstimatedTokens()), entity.getSummary(), entity.getCreatedAt());
    }

    private long value(Long value) { return value == null ? 0L : value; }
    private int value(Integer value) { return value == null ? 0 : value; }
}
