package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadService;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentThreadStore;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 执行回放测试：验证只从 Item 事实读取当前 Turn，且不触发任何执行入口。
 *
 * @author ethan
 * @date 2026-08-20
 */
class AgentExecutionTimelineServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void projectsCurrentTurnItemsInSequenceOrder() {
        TimelinePersistence persistence = new TimelinePersistence();
        AgentThreadModel thread = new AgentThreadModel("thread-1", "user-1", "Thread",
                AgentThreadStatusEnum.ACTIVE, null, null, 3, NOW, NOW);
        persistence.threads.add(thread);
        AgentTurnModel turn = new AgentTurnModel("turn-1", thread.threadId(), thread.userId(), "request-1",
                "查询", AgentTurnStatusEnum.COMPLETED, 1, null, null, NOW, NOW, NOW);
        persistence.turns.add(turn);
        persistence.items.add(new AgentItemModel("item-2", thread.threadId(), turn.turnId(), 2,
                AgentItemTypeEnum.ASSISTANT_MESSAGE, "done", NOW));
        persistence.items.add(new AgentItemModel("item-1", thread.threadId(), turn.turnId(), 1,
                AgentItemTypeEnum.USER_MESSAGE, "query", NOW));
        persistence.items.add(new AgentItemModel("item-other", thread.threadId(), "turn-other", 3,
                AgentItemTypeEnum.USER_MESSAGE, "other", NOW));

        AgentThreadService threads = new AgentThreadService(persistence, persistence, Clock.fixed(NOW, ZoneOffset.UTC));
        AgentExecutionTimelineModel timeline = new AgentExecutionTimelineService(persistence, threads)
                .get("user-1", "turn-1");

        assertEquals(List.of("item-1", "item-2"), timeline.items().stream()
                .map(AgentItemModel::itemId).toList());
    }

    @Test
    void stopsWhenTheStoreRepeatsAFullPageWithoutNewSequences() {
        TimelinePersistence persistence = new TimelinePersistence();
        AgentThreadModel thread = new AgentThreadModel("thread-1", "user-1", "Thread",
                AgentThreadStatusEnum.ACTIVE, null, null, 3, NOW, NOW);
        persistence.threads.add(thread);
        AgentTurnModel turn = new AgentTurnModel("turn-1", thread.threadId(), thread.userId(), "request-1",
                "查询", AgentTurnStatusEnum.COMPLETED, 1, null, null, NOW, NOW, NOW);
        persistence.turns.add(turn);
        for (int sequence = 1; sequence <= 500; sequence++) {
            persistence.repeatedPage.add(new AgentItemModel("item-" + sequence, thread.threadId(),
                    turn.turnId(), sequence, AgentItemTypeEnum.ASSISTANT_MESSAGE, "item", NOW));
        }

        AgentThreadService threads = new AgentThreadService(persistence, persistence, Clock.fixed(NOW, ZoneOffset.UTC));
        AgentExecutionTimelineModel timeline = new AgentExecutionTimelineService(persistence, threads)
                .get("user-1", "turn-1");

        assertEquals(500, timeline.items().size());
        assertEquals(2, persistence.listItemCalls);
    }

    private static final class TimelinePersistence implements AgentThreadStore, AgentTurnStore, AgentItemStore {
        private final List<AgentThreadModel> threads = new ArrayList<>();
        private final List<AgentTurnModel> turns = new ArrayList<>();
        private final List<AgentItemModel> items = new ArrayList<>();
        private final List<AgentItemModel> repeatedPage = new ArrayList<>();
        private int listItemCalls;

        @Override
        public void createThread(AgentThreadModel thread) { threads.add(thread); }

        @Override
        public Optional<AgentThreadModel> findThread(String userId, String threadId) {
            return threads.stream().filter(value -> value.userId().equals(userId)
                    && value.threadId().equals(threadId)).findFirst();
        }

        @Override
        public List<AgentThreadModel> listThreads(String userId) {
            return threads.stream().filter(value -> value.userId().equals(userId)).toList();
        }

        @Override
        public void updateThread(AgentThreadModel thread) { }

        @Override
        public Optional<AgentTurnModel> findTurn(String userId, String turnId) {
            return turns.stream().filter(value -> value.userId().equals(userId)
                    && value.turnId().equals(turnId)).findFirst();
        }

        @Override
        public Optional<AgentTurnModel> findTurnByRequest(String userId, String clientRequestId) {
            return turns.stream().filter(value -> value.userId().equals(userId)
                    && value.clientRequestId().equals(clientRequestId)).findFirst();
        }

        @Override
        public void createTurn(AgentTurnModel turn) { turns.add(turn); }

        @Override
        public boolean updateTurn(AgentTurnModel expected, AgentTurnModel next) { return true; }

        @Override
        public List<AgentTurnModel> listRecoverableTurns() { return List.of(); }

        @Override
        public long appendItem(AgentItemModel item) { return 0; }

        @Override
        public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
            listItemCalls++;
            if (!repeatedPage.isEmpty()) return repeatedPage;
            return items.stream().filter(value -> value.threadId().equals(threadId)
                    && value.sequence() > afterSequence).toList();
        }
    }
}
