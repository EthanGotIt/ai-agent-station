package cn.ethan.core.agent.service;

import cn.ethan.core.agent.enums.AgentStatusEnum;
import cn.ethan.core.agent.enums.OutputEventTypeEnum;
import cn.ethan.core.agent.model.OutputContextModel;
import cn.ethan.core.agent.model.OutputEventModel;
import cn.ethan.core.agent.model.StructuredResultModel;
import cn.ethan.core.workflow.model.WorkflowQuestionModel;
import cn.ethan.core.workflow.model.WorkflowRunModel;
import cn.ethan.core.agent.port.OutputObservationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 输出管理器：作为同步与流式执行共用的统一输出边界。
 *
 * @author ethan
 * @date 2026-08-05
 */
public final class OutputManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutputManager.class);
    private static final OutputObservationProvider NO_OP_OBSERVATION_PROVIDER =
            new OutputObservationProvider() {
                @Override
                public void recordEvent(OutputEventTypeEnum eventType) {
                    // 纯内核运行时不采集指标
                }

                @Override
                public void recordCompletion(String executorId, AgentStatusEnum status,
                                             Duration duration, int inputTokens, int outputTokens) {
                    // 纯内核运行时不采集指标
                }

                @Override
                public void recordError(String errorCode, Duration duration) {
                    // 纯内核运行时不采集指标
                }
            };
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)(bearer\\s+)[^\\s,;\"'}]+"
    );
    private static final Pattern SENSITIVE_PAIR = Pattern.compile(
            "(?i)([\"']?(?:api[-_]?key|password|token)[\"']?"
                    + "\\s*[:=]\\s*[\"']?)([^\"'\\s,;&}]+)"
    );

    private final OutputObservationProvider observationProvider;
    private final Clock clock;

    public OutputManager(Clock clock) {
        this(NO_OP_OBSERVATION_PROVIDER, clock);
    }

    public OutputManager(OutputObservationProvider observationProvider, Clock clock) {
        this.observationProvider = observationProvider;
        this.clock = clock;
    }

    public OutputContextModel start(String requestId) {
        LOGGER.info("Agent 请求开始，requestId={}", requestId);
        return new OutputContextModel(requestId, Instant.now(clock));
    }

    public void emit(Consumer<OutputEventModel> sink, OutputEventTypeEnum type, String value) {
        String safeValue = redact(value);
        observe(() -> observationProvider.recordEvent(type));
        if (sink != null) {
            sink.accept(new OutputEventModel(type, safeValue));
        }
    }

    /**
     * 转发已经结构化的执行器事件，保留 QuestionCard 与工具确认载荷。
     */
    public void emit(Consumer<OutputEventModel> sink, OutputEventModel event) {
        if (sink != null && event != null) {
            sink.accept(event);
        }
    }

    public void emitResult(Consumer<OutputEventModel> sink, StructuredResultModel result) {
        observe(() -> observationProvider.recordEvent(OutputEventTypeEnum.RESULT));
        if (sink != null && result != null) {
            sink.accept(OutputEventModel.result(result));
        }
    }

    public void emitWorkflowQuestion(
            Consumer<OutputEventModel> sink,
            WorkflowQuestionModel question,
            WorkflowRunModel workflowRun
    ) {
        observe(() -> observationProvider.recordEvent(OutputEventTypeEnum.WORKFLOW_QUESTION));
        if (sink != null && question != null && workflowRun != null) {
            sink.accept(OutputEventModel.workflowQuestion(question, workflowRun));
        }
    }

    public void complete(OutputContextModel context, String executorId, AgentStatusEnum status,
                         int inputTokens, int outputTokens) {
        Duration duration = duration(context);
        observe(() -> observationProvider.recordCompletion(
                normalizeLabel(executorId, "unknown"),
                status,
                duration,
                Math.max(inputTokens, 0),
                Math.max(outputTokens, 0)
        ));
        LOGGER.info(
                "Agent 请求结束，requestId={}，status={}，durationMs={}，inputTokens={}，outputTokens={}",
                context.requestId(),
                status,
                duration.toMillis(),
                Math.max(inputTokens, 0),
                Math.max(outputTokens, 0)
        );
    }

    public void error(OutputContextModel context, String errorCode) {
        String normalizedCode = normalizeErrorCode(errorCode);
        Duration duration = duration(context);
        observe(() -> observationProvider.recordError(normalizedCode, duration));
        LOGGER.warn(
                "Agent 请求失败，requestId={}，errorCode={}，durationMs={}",
                context.requestId(),
                normalizedCode,
                duration.toMillis()
        );
    }

    public String redact(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }
        String withoutBearer = BEARER_TOKEN.matcher(value).replaceAll("$1***");
        return SENSITIVE_PAIR.matcher(withoutBearer).replaceAll("$1***");
    }

    private Duration duration(OutputContextModel context) {
        Duration duration = Duration.between(context.startedAt(), Instant.now(clock));
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private String normalizeErrorCode(String errorCode) {
        String normalized = normalizeLabel(errorCode, "INTERNAL_ERROR")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_]+", "_");
        return normalized.isBlank() ? "INTERNAL_ERROR" : normalized;
    }

    private String normalizeLabel(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void observe(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException failure) {
            // 可观测基础设施异常不得改变 Agent 的业务结果
            LOGGER.debug("Agent 输出观测记录失败", failure);
        }
    }
}
