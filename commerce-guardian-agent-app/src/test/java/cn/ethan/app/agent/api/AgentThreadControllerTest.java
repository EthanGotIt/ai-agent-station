package cn.ethan.app.agent.api;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentThreadStore;
import cn.ethan.core.agent.thread.AgentThreadService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Thread Item 分页协议测试：过滤异常历史后，hasMore 不能因原始页大小虚增。
 *
 * @author ethan
 * @date 2026-08-28
 */
class AgentThreadControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

    @Test
    void staleItemsDoNotAdvertiseAnotherHistoryPage() {
        AgentThreadModel thread = new AgentThreadModel("thread-1", "user-1", "Thread",
                AgentThreadStatusEnum.ACTIVE, null, null, 4, NOW, NOW);
        AgentThreadStore threads = new AgentThreadStore() {
            @Override
            public void createThread(AgentThreadModel value) {
            }

            @Override
            public Optional<AgentThreadModel> findThread(String userId, String threadId) {
                return "user-1".equals(userId) && "thread-1".equals(threadId)
                        ? Optional.of(thread) : Optional.empty();
            }

            @Override
            public List<AgentThreadModel> listThreads(String userId) {
                return List.of(thread);
            }

            @Override
            public void updateThread(AgentThreadModel value) {
            }
        };
        AgentItemStore items = new AgentItemStore() {
            @Override
            public long appendItem(AgentItemModel item) {
                return item.sequence();
            }

            @Override
            public List<AgentItemModel> listItems(String userId, String threadId,
                                                  long afterSequence, int limit) {
                return List.of(
                        item("stale-1", 1),
                        item("stale-2", 2),
                        item("new-3", 3));
            }
        };
        AgentThreadController controller = new AgentThreadController(
                new AgentThreadService(threads, items, Clock.fixed(NOW, ZoneOffset.UTC)),
                new AgentUserContext(), null, null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "user-1");

        AgentItemPageResponseDto page = controller.items("thread-1", 2, 1, request);

        assertEquals(List.of("new-3"), page.items().stream().map(AgentItemDto::itemId).toList());
        assertEquals(3, page.nextAfterSequence());
        assertFalse(page.hasMore());
    }

    private AgentItemModel item(String itemId, long sequence) {
        return new AgentItemModel(itemId, "thread-1", "turn-1", sequence,
                AgentItemTypeEnum.USER_MESSAGE, "message", NOW);
    }
}
