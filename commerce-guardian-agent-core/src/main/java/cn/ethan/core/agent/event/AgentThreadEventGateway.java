package cn.ethan.core.agent.event;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentTurnModel;

/**
 * 类型职责：发布 Thread 执行状态和持久化 Item 的实时事件。
 *
 * @author ethan
 * @date 2026-08-19
 */
public interface AgentThreadEventGateway {

    void publish(AgentThreadEvent event);

    record AgentThreadEvent(
            String eventId,
            String threadId,
            String turnId,
            String type,
            String payload,
            long sequence,
            java.time.Instant at
    ) {
        /** Item 事件的 eventId 与持久化 itemId 相同，Turn 状态事件没有 itemId。 */
        public String itemId() {
            return sequence >= 0 ? eventId : null;
        }

        public java.time.Instant timestamp() {
            return at;
        }
    }

    default void itemCreated(AgentItemModel item) {
        publish(new AgentThreadEvent(
                item.itemId(), item.threadId(), item.turnId(), "item." + item.type().name().toLowerCase(),
                item.payload(), item.sequence(), item.createdAt()
        ));
    }

    default void turnUpdated(AgentTurnModel turn) {
        publish(new AgentThreadEvent(
                turn.turnId() + ":" + turn.status(), turn.threadId(), turn.turnId(),
                "turn." + turn.status().name().toLowerCase(), turn.status().name(), -1,
                turn.finishedAt() == null ? turn.createdAt() : turn.finishedAt()
        ));
    }
}
