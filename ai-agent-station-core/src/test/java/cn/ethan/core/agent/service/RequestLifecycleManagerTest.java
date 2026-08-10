package cn.ethan.core.agent.service;

import cn.ethan.core.agent.enums.RequestLifecycleStateEnum;
import cn.ethan.core.agent.exception.RequestLifecycleException;
import cn.ethan.core.agent.model.RequestHandleModel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求生命周期管理器测试：验证排队状态、请求唯一性和精确取消。
 *
 * @author ethan
 * @date 2026-08-06
 */
class RequestLifecycleManagerTest {

    @Test
    void followsPreparedQueuedActiveAndCompletedStates() {
        RequestLifecycleManager manager = new RequestLifecycleManager(Duration.ofMinutes(1));
        manager.prepare("request-1", "user-1", "session-1");

        assertEquals(RequestLifecycleStateEnum.PREPARED, manager.state("request-1"));

        manager.markQueued("request-1");
        assertEquals(RequestLifecycleStateEnum.QUEUED, manager.state("request-1"));

        manager.activate("request-1");
        assertEquals(RequestLifecycleStateEnum.ACTIVE, manager.state("request-1"));

        manager.complete("request-1");
        assertEquals(RequestLifecycleStateEnum.COMPLETED, manager.state("request-1"));
    }

    @Test
    void rejectsDuplicateRequestIdDuringTerminalRetention() {
        RequestLifecycleManager manager = new RequestLifecycleManager(Duration.ofMinutes(1));
        manager.prepare("request-1", "user-1", "session-1");
        manager.markQueued("request-1");
        manager.activate("request-1");
        manager.complete("request-1");

        RequestLifecycleException exception = assertThrows(
                RequestLifecycleException.class,
                () -> manager.prepare("request-1", "user-1", "session-2")
        );

        assertEquals("REQUEST_ID_CONFLICT", exception.getCode());
        assertEquals("request-1", exception.getRelatedRequestId());
    }

    @Test
    void cancellationOnlyTargetsOwnedExecution() {
        RequestLifecycleManager manager = new RequestLifecycleManager(Duration.ofMinutes(1));
        RequestHandleModel handle = manager.prepare("request-1", "user-1", "session-1");
        manager.markQueued("request-1");
        manager.activate("request-1");

        assertFalse(manager.cancelActive("request-1", "user-2"));
        assertFalse(handle.token().isCancelled());

        assertTrue(manager.cancelActive("request-1", "user-1"));
        assertTrue(handle.token().isCancelled());
        assertEquals(RequestLifecycleStateEnum.CANCELLING, manager.state("request-1"));

        manager.markCancelled("request-1");
        assertEquals(RequestLifecycleStateEnum.CANCELLED, manager.state("request-1"));
    }

    @Test
    void terminalEntriesExpireAfterTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-05T08:00:00Z"));
        RequestLifecycleManager manager = new RequestLifecycleManager(
                Duration.ofMinutes(1),
                clock
        );
        manager.prepare("request-1", "user-1", "session-1");
        manager.fail("request-1");

        clock.advance(Duration.ofSeconds(61));
        manager.cleanup();

        assertNull(manager.state("request-1"));
    }

    @Test
    void rejectsInvalidTransitionAndNonPositiveTtl() {
        RequestLifecycleManager manager = new RequestLifecycleManager(Duration.ofMinutes(1));
        manager.prepare("request-1", "user-1", "session-1");

        assertThrows(IllegalStateException.class, () -> manager.activate("request-1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RequestLifecycleManager(Duration.ZERO)
        );
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
