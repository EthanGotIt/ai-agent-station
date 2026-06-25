package cn.ethan.ai.domain.agent.service.execute.springai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.CompositeCompactionTrigger;
import org.springframework.ai.session.compaction.CompactionStrategy;
import org.springframework.ai.session.compaction.CompactionTrigger;
import org.springframework.ai.session.compaction.RecursiveSummarizationCompactionStrategy;
import org.springframework.ai.session.compaction.TokenCountTrigger;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.compaction.TurnWindowCompactionStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring AI Community Session 的默认装配，包含 Compaction Trigger 与 Strategy 配置。
 *
 * <p>Trigger：任一条件触发 — token 估算超过 6000 或完整 Turn 数超过 10。</p>
 * <p>Strategy：优先使用 LLM 递归摘要（保留最近 8 个事件原文，旧事件由 ChatClient 摘要合并）；
 * 若当前上下文无可用 ChatClient（如 Armory 尚未装配），回退到保留最近 8 轮完整 Turn。</p>
 * <p>升级路径：当 armory 装配完 ChatClient 后，可替换为
 * {@link RecursiveSummarizationCompactionStrategy} 以获得更好的知识保留效果。</p>
 */
@Configuration
public class SpringAiSessionConfiguration {

    private static final int TOKEN_COUNT_THRESHOLD = 6000;

    private static final int TURN_COUNT_THRESHOLD = 10;

    private static final int MAX_TURNS_TO_KEEP = 8;

    private static final int MAX_EVENTS_TO_KEEP = 8;

    @Bean
    @ConditionalOnMissingBean
    public SessionService springAiSessionService() {
        return DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionMemoryAdvisor sessionMemoryAdvisor(
            SessionService sessionService,
            HeuristicTokenCountEstimator tokenCountEstimator,
            @Autowired(required = false) List<ChatClient> chatClients) {

        CompactionTrigger trigger = CompositeCompactionTrigger.anyOf(
                TokenCountTrigger.builder()
                        .threshold(TOKEN_COUNT_THRESHOLD)
                        .tokenCountEstimator(tokenCountEstimator)
                        .build(),
                new TurnCountTrigger(TURN_COUNT_THRESHOLD)
        );

        CompactionStrategy strategy = buildStrategy(tokenCountEstimator, chatClients);

        return SessionMemoryAdvisor.builder(sessionService)
                .defaultUserId("ai-agent-station")
                .compactionTrigger(trigger)
                .compactionStrategy(strategy)
                .build();
    }

    private CompactionStrategy buildStrategy(HeuristicTokenCountEstimator tokenCountEstimator,
                                              List<ChatClient> chatClients) {
        ChatClient summarizationClient = (chatClients != null && !chatClients.isEmpty())
                ? chatClients.get(0) : null;
        if (summarizationClient != null) {
            return RecursiveSummarizationCompactionStrategy.builder(summarizationClient)
                    .maxEventsToKeep(MAX_EVENTS_TO_KEEP)
                    .tokenCountEstimator(tokenCountEstimator)
                    .build();
        }
        // ChatClient 尚未装配时使用安全的 Turn 窗口策略，不依赖额外模型调用。
        return TurnWindowCompactionStrategy.builder()
                .maxTurns(MAX_TURNS_TO_KEEP)
                .tokenCountEstimator(tokenCountEstimator)
                .build();
    }
}
