package cn.ethan.core.agent.service;

import cn.ethan.core.agent.enums.AgentStatusEnum;
import cn.ethan.core.agent.enums.OutputEventTypeEnum;
import cn.ethan.core.agent.model.OutputContextModel;
import cn.ethan.core.agent.model.OutputEventModel;
import cn.ethan.core.agent.port.OutputObservationProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出管理器测试：验证敏感信息脱敏、耗时和 Token 观测边界。
 *
 * @author ethan
 * @date 2026-08-05
 */
class OutputManagerTest {

    @Test
    void redactsSensitiveEventBeforeSending() {
        RecordingObservationProvider provider = new RecordingObservationProvider();
        OutputManager manager = new OutputManager(provider, Clock.systemUTC());
        List<OutputEventModel> events = new ArrayList<>();

        manager.emit(
                events::add,
                OutputEventTypeEnum.CONTENT,
                "Authorization: Bearer secret-token api_key=raw-key"
        );

        assertEquals(1, events.size());
        assertFalse(events.get(0).value().contains("secret-token"));
        assertFalse(events.get(0).value().contains("raw-key"));
        assertEquals(List.of(OutputEventTypeEnum.CONTENT), provider.events);
    }

    @Test
    void redactsQuotedAndUrlEncodedSecrets() {
        OutputManager manager = new OutputManager(Clock.systemUTC());

        String redacted = manager.redact(
                "{\"api_key\":\"json-secret\",\"password\":\"json-password\"} "
                        + "https://example.com?token=url-secret&next=1"
        );

        assertFalse(redacted.contains("json-secret"));
        assertFalse(redacted.contains("json-password"));
        assertFalse(redacted.contains("url-secret"));
        assertTrue(redacted.contains("\"api_key\":\"***\""));
        assertTrue(redacted.contains("token=***&next=1"));
    }

    @Test
    void recordsDurationAndTokenUsage() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-05T08:00:00Z"));
        RecordingObservationProvider provider = new RecordingObservationProvider();
        OutputManager manager = new OutputManager(provider, clock);
        OutputContextModel context = manager.start("request-1");

        clock.advance(Duration.ofMillis(250));
        manager.complete(context, "react", AgentStatusEnum.COMPLETED, 12, 8);

        assertEquals(Duration.ofMillis(250), provider.duration);
        assertEquals(12, provider.inputTokens);
        assertEquals(8, provider.outputTokens);
    }

    private static final class RecordingObservationProvider
            implements OutputObservationProvider {

        private final List<OutputEventTypeEnum> events = new ArrayList<>();
        private Duration duration;
        private int inputTokens;
        private int outputTokens;

        @Override
        public void recordEvent(OutputEventTypeEnum type) {
            events.add(type);
        }

        @Override
        public void recordCompletion(String executorId, AgentStatusEnum status,
                                     Duration duration, int inputTokens,
                                     int outputTokens) {
            this.duration = duration;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

        @Override
        public void recordError(String errorCode, Duration duration) {
            this.duration = duration;
        }
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
