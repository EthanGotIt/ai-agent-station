package cn.ethan.core.agent.port;

import cn.ethan.core.agent.enums.OutputEventTypeEnum;
import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.model.AgentMemoryEntryModel;
import cn.ethan.core.agent.model.ConversationMessageModel;
import cn.ethan.core.agent.model.OutputEventModel;
import cn.ethan.core.agent.model.ReActResultModel;
import cn.ethan.core.agent.model.ToolInterventionRequestModel;
import cn.ethan.core.agent.support.CancellationToken;

import java.util.function.Consumer;
import java.util.List;

/**
 * ReAct 执行器：定义复杂只读场景兜底能力的执行端口。
 *
 * @author ethan
 * @date 2026-08-05
 */
@FunctionalInterface
public interface ReActExecutor {

    ReActResultModel execute(AgentRequestModel request, String userId, CancellationToken token);

    default void interrupt(String requestId, String userId, String sessionId) {
        // 未提供主动中断能力的适配器仍由 CancellationToken 协作式终止
    }

    /**
     * 提交正在等待的 AgentScope 工具确认；此操作不参与会话执行队列。
     *
     * @return 是否成功投递给当前仍在等待的 ReAct 回合
     */
    default boolean decide(ToolInterventionRequestModel request, String userId) {
        return false;
    }

    default ReActResultModel execute(AgentRequestModel request, String userId,
                                     CancellationToken token,
                                     Consumer<OutputEventModel> sink) {
        ReActResultModel result = execute(request, userId, token);
        if (sink != null) {
            sink.accept(new OutputEventModel(
                    OutputEventTypeEnum.CONTENT,
                    result.finalContent()
            ));
        }
        return result;
    }

    default ReActResultModel execute(
            AgentRequestModel request,
            String userId,
            List<ConversationMessageModel> history,
            CancellationToken token,
            Consumer<OutputEventModel> sink
    ) {
        return execute(request, userId, token, sink);
    }

    /**
     * 向 ReAct 传递受控记忆上下文；默认实现保持旧适配器兼容。
     */
    default ReActResultModel execute(
            AgentRequestModel request,
            String userId,
            List<ConversationMessageModel> history,
            List<AgentMemoryEntryModel> memories,
            CancellationToken token,
            Consumer<OutputEventModel> sink
    ) {
        return execute(request, userId, history, token, sink);
    }
}
