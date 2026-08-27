package cn.ethan.infrastructure.agent.coordination.springai;

import cn.ethan.core.agent.execution.AgentRuntimeMetrics;
import cn.ethan.core.agent.workflow.AgentQuestionCardModel;
import cn.ethan.core.agent.workflow.AgentQuestionCardStore;
import cn.ethan.core.agent.workflow.AgentQuestionCardStatusEnum;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * request_user_input 工具契约测试：确认模型提问落到独立 QuestionCard，且拒绝授权字段。
 *
 * @author ethan
 * @date 2026-08-27
 */
class SpringAiRequestUserInputToolTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void createsQuestionCardAndRecordsQuestionItem() {
        AtomicReference<AgentQuestionCardModel> created = new AtomicReference<>();
        SpringAiAgentTurnCoordinator.RequestUserInputTools tools = new SpringAiAgentTurnCoordinator.RequestUserInputTools(
                thread(), turn(), store(created), invocation());

        assertEquals("已向用户提出问题，等待回答。", tools.requestUserInput(
                "缺少订单号", "请补充订单号", "[{\"name\":\"orderId\",\"required\":true,\"maxLength\":64}]"));
        assertEquals("AGENT", created.get().resumeTarget().name());
    }

    @Test
    void doesNotAllowAuthorizationPurposeInQuestionCard() {
        SpringAiAgentTurnCoordinator.RequestUserInputTools tools = new SpringAiAgentTurnCoordinator.RequestUserInputTools(
                thread(), turn(), store(new AtomicReference<>()), invocation());

        assertThrows(IllegalArgumentException.class, () -> tools.requestUserInput(
                "确认", "请确认", "[{\"name\":\"authorization\",\"purpose\":\"AUTHORIZATION\"}]"));
    }

    private SpringAiAgentTurnCoordinator.WorkflowInvocation invocation() {
        return new SpringAiAgentTurnCoordinator.WorkflowInvocation(
                null, Clock.fixed(NOW, ZoneOffset.UTC), AgentRuntimeMetrics.noop());
    }

    private cn.ethan.core.agent.thread.AgentThreadModel thread() {
        return new cn.ethan.core.agent.thread.AgentThreadModel(
                "thread-1", "user-1", "测试", cn.ethan.core.agent.thread.AgentThreadStatusEnum.ACTIVE,
                null, null, 0, NOW, NOW);
    }

    private cn.ethan.core.agent.thread.AgentTurnModel turn() {
        return new cn.ethan.core.agent.thread.AgentTurnModel(
                "turn-1", "thread-1", "user-1", "request-1", "询问",
                cn.ethan.core.agent.thread.AgentTurnStatusEnum.ACTIVE, 0, null, null, NOW, NOW, null);
    }

    private AgentQuestionCardStore store(AtomicReference<AgentQuestionCardModel> created) {
        return new AgentQuestionCardStore() {
            @Override
            public Optional<AgentQuestionCardModel> find(String userId, String questionId) {
                return Optional.empty();
            }

            @Override
            public Optional<AgentQuestionCardModel> findOpen(String userId, String threadId) {
                return Optional.empty();
            }

            @Override
            public void create(AgentQuestionCardModel question) {
                created.set(question);
            }

            @Override
            public OptionalLong reserveAnswerTurn(String userId, String questionId, long expectedVersion,
                                                   String answerTurnId) {
                return OptionalLong.empty();
            }

            @Override
            public OptionalLong markAnswerTurnEnqueued(String userId, String questionId, long expectedVersion,
                                                        String answerTurnId) {
                return OptionalLong.empty();
            }

            @Override
            public boolean releaseAnswerTurn(String userId, String questionId, long expectedVersion,
                                             String answerTurnId) {
                return false;
            }

            @Override
            public boolean closeAnswerTurn(String userId, String questionId, long expectedVersion,
                                            String answerTurnId, AgentQuestionCardStatusEnum terminalStatus,
                                            Instant answeredAt) {
                return false;
            }
        };
    }
}
