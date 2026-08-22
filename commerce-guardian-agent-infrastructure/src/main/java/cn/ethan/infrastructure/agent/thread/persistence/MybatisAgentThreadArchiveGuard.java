package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentThreadArchiveGuard;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.infrastructure.agent.action.persistence.ExternalActionCommandMapper;
import org.springframework.stereotype.Component;

/**
 * 类型职责：以数据库当前事实阻止仍可能执行或等待用户输入的 Thread 进入回收站。
 *
 * @author ethan
 * @date 2026-08-23
 */
@Component
public final class MybatisAgentThreadArchiveGuard implements AgentThreadArchiveGuard {

    private final AgentTurnMapper turns;
    private final AgentWorkflowQuestionMapper questions;
    private final ExternalActionCommandMapper commands;

    public MybatisAgentThreadArchiveGuard(
            AgentTurnMapper turns,
            AgentWorkflowQuestionMapper questions,
            ExternalActionCommandMapper commands
    ) {
        this.turns = turns;
        this.questions = questions;
        this.commands = commands;
    }

    @Override
    public void ensureCanArchive(String userId, String threadId) {
        if (turns.countActiveByThread(userId, threadId) > 0) {
            throw blocked("THREAD_HAS_ACTIVE_TURN", "当前对话仍在处理中，完成后才能移入回收站");
        }
        if (questions.selectOpen(userId, threadId) != null) {
            throw blocked("THREAD_HAS_OPEN_QUESTION", "当前对话仍等待确认，回答或取消后才能移入回收站");
        }
        if (commands.countUnfinishedByThread(userId, threadId) > 0) {
            throw blocked("THREAD_HAS_EXTERNAL_ACTION", "当前对话仍有未完成订单操作，完成后才能移入回收站");
        }
    }

    private AgentThreadConflictException blocked(String code, String message) {
        return new AgentThreadConflictException(code, message);
    }
}
