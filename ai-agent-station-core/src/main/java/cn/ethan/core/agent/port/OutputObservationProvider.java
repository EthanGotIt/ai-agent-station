package cn.ethan.core.agent.port;

import cn.ethan.core.agent.enums.AgentStatusEnum;
import cn.ethan.core.agent.enums.OutputEventTypeEnum;

import java.time.Duration;

/**
 * 输出观测提供器：隔离 core 与具体指标系统，并接收低基数观测数据。
 *
 * @author ethan
 * @date 2026-08-05
 */
public interface OutputObservationProvider {

    void recordEvent(OutputEventTypeEnum type);

    void recordCompletion(String executorId, AgentStatusEnum status, Duration duration,
                          int inputTokens, int outputTokens);

    void recordError(String errorCode, Duration duration);

    default void recordWorkflowTransition(String workflowId, String status) {
        // optional observation
    }

    default void recordMemoryExtraction(String outcome) {
        // optional observation
    }

    default void recordMemoryRetrieval(String consumer, int entryCount, int characterCount) {
        // optional observation
    }

    default void recordIntervention(String outcome, Duration waitDuration) {
        // optional observation
    }
}
