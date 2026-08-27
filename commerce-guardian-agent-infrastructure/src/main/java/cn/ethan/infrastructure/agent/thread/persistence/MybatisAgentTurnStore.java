package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentQuestionAnswerInput;
import cn.ethan.core.agent.thread.AgentTurnInputKindEnum;
import cn.ethan.core.agent.coordination.AgentContinuationInput;
import cn.ethan.core.agent.coordination.AgentOrderActionInput;
import cn.ethan.core.agent.thread.AgentWorkflowDecisionInput;
import cn.ethan.core.agent.workflow.AgentWorkflowOwnerRecoveryCandidate;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 类型职责：只持久化 Turn 生命周期、请求幂等和重启恢复状态。
 * 该适配器需要保留可代理性，以承接 Spring 的异常翻译和事务边界。
 *
 * @author ethan
 * @date 2026-08-20
 */
@Repository
public class MybatisAgentTurnStore implements AgentTurnStore {

    private final AgentTurnMapper mapper;
    private final AgentItemMapper itemMapper;
    private final AgentThreadMapper threadMapper;
    private final JacksonAgentOrderActionCodec orderActionCodec;
    private final JacksonAgentContinuationCodec continuationCodec;
    private final JacksonAgentWorkflowDecisionCodec decisionCodec;
    private final JacksonAgentQuestionAnswerCodec questionAnswerCodec;

    public MybatisAgentTurnStore(
            AgentTurnMapper mapper,
            AgentItemMapper itemMapper,
            AgentThreadMapper threadMapper
    ) {
        this(mapper, itemMapper, threadMapper,
                new JacksonAgentOrderActionCodec(new tools.jackson.databind.ObjectMapper()),
                new JacksonAgentContinuationCodec(new tools.jackson.databind.ObjectMapper()),
                new JacksonAgentWorkflowDecisionCodec(new tools.jackson.databind.ObjectMapper()),
                new JacksonAgentQuestionAnswerCodec(new tools.jackson.databind.ObjectMapper()));
    }

    @Autowired
    public MybatisAgentTurnStore(
            AgentTurnMapper mapper,
            AgentItemMapper itemMapper,
            AgentThreadMapper threadMapper,
            JacksonAgentOrderActionCodec orderActionCodec,
            JacksonAgentContinuationCodec continuationCodec,
            JacksonAgentWorkflowDecisionCodec decisionCodec,
            JacksonAgentQuestionAnswerCodec questionAnswerCodec
    ) {
        this.mapper = mapper;
        this.itemMapper = itemMapper;
        this.threadMapper = threadMapper;
        this.orderActionCodec = orderActionCodec;
        this.continuationCodec = continuationCodec;
        this.decisionCodec = decisionCodec;
        this.questionAnswerCodec = questionAnswerCodec;
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
        AgentThreadEntity thread = threadMapper.selectForUpdate(turn.threadId());
        if (thread == null) {
            throw new IllegalStateException("Thread 不存在：" + turn.threadId());
        }
        if (!"ACTIVE".equals(thread.getStatus())) {
            throw new AgentThreadConflictException("THREAD_ARCHIVED", "回收站中的对话不能继续接收新消息");
        }
        mapper.insert(toEntity(turn));
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
        AgentQuestionAnswerInput questionAnswer = next.questionAnswerInput();
        AgentOrderActionInput orderAction = next.orderActionInput();
        AgentWorkflowDecisionInput decision = next.workflowDecisionInput();
        int updated = mapper.update(null, new UpdateWrapper<AgentTurnEntity>()
                .eq("TURN_ID", next.turnId())
                .eq("USER_ID", next.userId())
                .eq("VERSION_NO", expected.version())
                .set("STATUS", next.status().name())
                .set("QUEUE_POSITION", next.queuePosition())
                .set("WORKFLOW_RUN_ID", next.workflowRunId())
                .set("QUESTION_CARD_ID", questionAnswer == null ? null : questionAnswer.questionId())
                .set("WORKFLOW_CHECKPOINT_ID", decision == null ? null : decision.checkpointId())
                .set("QUESTION_ANSWER_JSON", questionAnswerCodec.encode(questionAnswer))
                .set("INPUT_KIND", next.inputKind().name())
                .set("ORDER_ACTION_JSON", orderActionCodec.encode(orderAction))
                .set("CONTINUATION_JSON", continuationCodec.encode(next.continuationInput()))
                .set("WORKFLOW_DECISION_JSON", decisionCodec.encode(decision))
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
    public List<AgentWorkflowOwnerRecoveryCandidate> listWorkflowOwnerRecoveryCandidates() {
        return mapper.selectWorkflowOwnerRecoveryCandidates().stream()
                .map(row -> new AgentWorkflowOwnerRecoveryCandidate(
                        findTurn(row.getUserId(), row.getTurnId())
                                .orElseThrow(() -> new IllegalStateException(
                                        "Workflow owner Turn 在恢复查询后消失：" + row.getTurnId())),
                        AgentWorkflowStatusEnum.valueOf(row.getWorkflowRunStatus()),
                        Integer.valueOf(1).equals(row.getOpenInteraction())))
                .toList();
    }

    private AgentTurnEntity toEntity(AgentTurnModel model) {
        AgentTurnEntity entity = new AgentTurnEntity();
        entity.setTurnId(model.turnId());
        entity.setThreadId(model.threadId());
        entity.setUserId(model.userId());
        entity.setClientRequestId(model.clientRequestId());
        entity.setInputText(model.input());
        entity.setInputKind(model.inputKind().name());
        entity.setOrderActionJson(orderActionCodec.encode(model.orderActionInput()));
        entity.setContinuationJson(continuationCodec.encode(model.continuationInput()));
        entity.setStatus(model.status().name());
        entity.setQueuePosition(model.queuePosition());
        entity.setWorkflowRunId(model.workflowRunId());
        AgentQuestionAnswerInput questionAnswer = model.questionAnswerInput();
        if (questionAnswer != null) {
            entity.setQuestionCardId(questionAnswer.questionId());
        }
        if (model.workflowDecisionInput() != null) {
            entity.setWorkflowCheckpointId(model.workflowDecisionInput().checkpointId());
        }
        entity.setQuestionAnswerJson(questionAnswerCodec.encode(questionAnswer));
        entity.setWorkflowDecisionJson(decisionCodec.encode(model.workflowDecisionInput()));
        entity.setErrorCode(model.errorCode());
        entity.setCreatedAt(model.createdAt());
        entity.setStartedAt(model.startedAt());
        entity.setFinishedAt(model.finishedAt());
        entity.setVersionNo(model.version());
        return entity;
    }

    private AgentTurnModel toModel(AgentTurnEntity entity) {
        AgentTurnInputKindEnum inputKind = inputKind(entity.getInputKind(),
                entity.getQuestionAnswerJson(), entity.getOrderActionJson(), entity.getContinuationJson(),
                entity.getWorkflowDecisionJson());
        AgentOrderActionInput orderAction = orderActionCodec.decode(entity.getOrderActionJson());
        AgentContinuationInput continuation = continuationCodec.decode(entity.getContinuationJson());
        AgentWorkflowDecisionInput decision = decisionCodec.decode(entity.getWorkflowDecisionJson());
        AgentQuestionAnswerInput questionAnswer = questionAnswerCodec.decode(entity.getQuestionAnswerJson());
        return new AgentTurnModel(entity.getTurnId(), entity.getThreadId(), entity.getUserId(),
                entity.getClientRequestId(), entity.getInputText(), AgentTurnStatusEnum.valueOf(entity.getStatus()),
                value(entity.getQueuePosition()), entity.getWorkflowRunId(), entity.getErrorCode(), entity.getCreatedAt(),
                entity.getStartedAt(), entity.getFinishedAt(), questionAnswer,
                value(entity.getVersionNo()), inputKind, orderAction, continuation, decision);
    }

    private AgentTurnInputKindEnum inputKind(
            String persisted,
            String questionAnswerJson,
            String orderActionJson,
            String continuationJson,
            String workflowDecisionJson
    ) {
        if (workflowDecisionJson != null && !workflowDecisionJson.isBlank()) {
            return AgentTurnInputKindEnum.WORKFLOW_DECISION;
        }
        if (questionAnswerJson != null && !questionAnswerJson.isBlank()) {
            return AgentTurnInputKindEnum.QUESTION_ANSWER;
        }
        if (continuationJson != null && !continuationJson.isBlank()) {
            return AgentTurnInputKindEnum.AGENT_CONTINUATION;
        }
        if (orderActionJson != null && !orderActionJson.isBlank()) {
            return AgentTurnInputKindEnum.ORDER_ACTION;
        }
        if (persisted != null && !persisted.isBlank()
                && !"WORKFLOW_ANSWER".equalsIgnoreCase(persisted)) {
            return AgentTurnInputKindEnum.valueOf(persisted);
        }
        // 旧版本 WORKFLOW_ANSWER 只作为历史输入标记；新 Runtime 不再恢复或执行该路径。
        return AgentTurnInputKindEnum.MESSAGE;
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
