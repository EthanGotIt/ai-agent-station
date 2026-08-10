package cn.ethan.core.agent.port;

import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.model.ConversationMessageModel;
import cn.ethan.core.agent.model.RouteDecisionModel;
import cn.ethan.core.agent.support.CancellationToken;

import java.util.List;

/**
 * 路由决策提供器：定义结构化意图路由能力的调用端口。
 *
 * @author ethan
 * @date 2026-08-05
 */
@FunctionalInterface
public interface RouteDecisionProvider {

    RouteDecisionModel decide(AgentRequestModel request, String userId, CancellationToken token);

    default RouteDecisionModel decide(
            AgentRequestModel request,
            String userId,
            List<ConversationMessageModel> history,
            CancellationToken token
    ) {
        return decide(request, userId, token);
    }
}
