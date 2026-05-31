package cn.ethan.ai.infrastructure.adapter.port;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.Collections;
import java.util.List;

/**
 * Flow Runtime 顾问策略。Session 历史由显式持久化上下文提供，内部 prompt 不写入 ChatMemory。
 */
public final class AgentRuntimeAdvisorPolicy {

    private AgentRuntimeAdvisorPolicy() {
    }

    public static List<Advisor> filterInternalRuntimeAdvisors(List<Advisor> advisors) {
        if (advisors == null || advisors.isEmpty()) {
            return Collections.emptyList();
        }
        return advisors.stream()
                .filter(advisor -> !(advisor instanceof MessageChatMemoryAdvisor))
                .toList();
    }

}
