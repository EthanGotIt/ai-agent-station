package cn.ethan.core.agent.service;

import cn.ethan.core.agent.enums.AgentMemoryCategoryEnum;
import cn.ethan.core.agent.enums.AgentMemoryOriginEnum;
import cn.ethan.core.agent.enums.AgentMemorySourceEnum;
import cn.ethan.core.agent.exception.AgentMemoryConflictException;
import cn.ethan.core.agent.exception.AgentMemoryNotFoundException;
import cn.ethan.core.agent.model.AgentMemoryCandidateModel;
import cn.ethan.core.agent.model.AgentMemoryEntryModel;
import cn.ethan.core.agent.model.AgentMemoryEvidenceModel;
import cn.ethan.core.agent.model.AgentMemoryExtractionInputModel;
import cn.ethan.core.agent.model.AgentMemoryOptionsModel;
import cn.ethan.core.agent.model.AgentMemorySourceModel;
import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.model.AgentResponseModel;
import cn.ethan.core.agent.port.AgentMemoryStore;
import cn.ethan.core.agent.port.OutputObservationProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Agent 记忆服务：集中执行会话隔离、受控键空间、tombstone 与检索预算规则。
 *
 * @author ethan
 * @date 2026-08-10
 */
public final class AgentMemoryService {

    private static final Set<String> PREFERENCE_KEYS = Set.of(
            "response.language", "response.format", "response.detail"
    );
    private static final Set<String> TASK_CONTEXT_KEYS = Set.of("order.id", "refund.reason");
    private static final int STORE_LIMIT = 100;
    private static final OutputObservationProvider NO_OP_OBSERVATION = new OutputObservationProvider() {
        @Override
        public void recordEvent(cn.ethan.core.agent.enums.OutputEventTypeEnum type) {
        }

        @Override
        public void recordCompletion(
                String executorId,
                cn.ethan.core.agent.enums.AgentStatusEnum status,
                Duration duration,
                int inputTokens,
                int outputTokens
        ) {
        }

        @Override
        public void recordError(String errorCode, Duration duration) {
        }
    };

    private final boolean generationEnabled;
    private final boolean usageEnabled;
    private final double minimumAutoConfidence;
    private final AgentMemoryStore store;
    private final Clock clock;
    private final OutputObservationProvider observations;

    public AgentMemoryService(
            boolean generationEnabled,
            boolean usageEnabled,
            double minimumAutoConfidence,
            AgentMemoryStore store,
            Clock clock
    ) {
        this(generationEnabled, usageEnabled, minimumAutoConfidence, store, clock, NO_OP_OBSERVATION);
    }

    public AgentMemoryService(
            boolean generationEnabled,
            boolean usageEnabled,
            double minimumAutoConfidence,
            AgentMemoryStore store,
            Clock clock,
            OutputObservationProvider observations
    ) {
        if (minimumAutoConfidence < 0.0 || minimumAutoConfidence > 1.0) {
            throw new IllegalArgumentException("minimumAutoConfidence is invalid");
        }
        this.generationEnabled = generationEnabled;
        this.usageEnabled = usageEnabled;
        this.minimumAutoConfidence = minimumAutoConfidence;
        this.store = store;
        this.clock = clock;
        this.observations = observations == null ? NO_OP_OBSERVATION : observations;
    }

    public AgentMemoryService(boolean recordingEnabled, AgentMemoryStore store, Clock clock) {
        this(recordingEnabled, false, 0.75, store, clock);
    }

    public boolean shouldGenerate(AgentMemoryOptionsModel options) {
        return (options == null ? AgentMemoryOptionsModel.DEFAULT : options)
                .generationEnabled(generationEnabled);
    }

    public boolean shouldUse(AgentMemoryOptionsModel options) {
        return (options == null ? AgentMemoryOptionsModel.DEFAULT : options)
                .usageEnabled(usageEnabled);
    }

    public Optional<AgentMemoryExtractionInputModel> extractionInput(
            AgentRequestModel request,
            String userId,
            AgentResponseModel response
    ) {
        if (!shouldGenerate(request.memory()) || response.status() != cn.ethan.core.agent.enums.AgentStatusEnum.COMPLETED
                || response.content().isBlank()) {
            return Optional.empty();
        }
        AgentMemorySourceEnum sourceType = response.route() == cn.ethan.core.agent.enums.RouteTypeEnum.REACT
                ? AgentMemorySourceEnum.REACT
                : response.route() == cn.ethan.core.agent.enums.RouteTypeEnum.WORKFLOW
                ? AgentMemorySourceEnum.WORKFLOW : null;
        if (sourceType == null || containsSensitiveContent(request.normalizedMessage())
                || containsSensitiveContent(response.content())) {
            return Optional.empty();
        }
        return Optional.of(new AgentMemoryExtractionInputModel(
                userId, request.sessionId(), request.requestId(), sourceType,
                request.normalizedMessage(), response.content()
        ));
    }

    public void persistAutomatic(
            List<AgentMemoryExtractionInputModel> inputs,
            List<AgentMemoryCandidateModel> candidates
    ) {
        if (inputs == null || inputs.isEmpty() || candidates == null || candidates.isEmpty()) {
            return;
        }
        AgentMemoryExtractionInputModel latest = inputs.get(inputs.size() - 1);
        Instant now = clock.instant();
        AgentMemorySourceModel source = new AgentMemorySourceModel(
                UUID.randomUUID().toString(), latest.userId(), latest.sessionId(), latest.requestId(),
                latest.sourceType(), now
        );
        store.createSource(source);
        boolean persisted = false;
        for (AgentMemoryCandidateModel candidate : candidates) {
            String normalizedValue = normalizeValue(candidate.category(), candidate.memoryKey(), candidate.value());
            if (!isAllowedKey(candidate.category(), candidate.memoryKey()) || normalizedValue == null
                    || candidate.confidence() < minimumAutoConfidence
                    || containsSensitiveContent(candidate.value())) {
                continue;
            }
            Optional<AgentMemoryEntryModel> existing = store.findOwnedByKey(
                    latest.userId(), latest.sessionId(), candidate.category().name(), candidate.memoryKey()
            );
            if (existing.isPresent() && (existing.get().deleted()
                    || existing.get().origin() == AgentMemoryOriginEnum.MANUAL)) {
                continue;
            }
            AgentMemoryEntryModel next = new AgentMemoryEntryModel(
                    existing.map(AgentMemoryEntryModel::entryId).orElseGet(() -> UUID.randomUUID().toString()),
                    source.sourceId(), latest.userId(), latest.sessionId(), candidate.category(),
                    candidate.memoryKey(), normalizedValue, AgentMemoryOriginEnum.AUTO,
                    candidate.confidence(), existing.map(entry -> entry.version() + 1).orElse(0L),
                    false, expiry(candidate.category(), now),
                    existing.map(AgentMemoryEntryModel::createdAt).orElse(now), now
            );
            if (existing.isPresent()) {
                if (!store.update(existing.get(), next)) {
                    continue;
                }
            } else {
                store.createEntry(next);
            }
            for (AgentMemoryExtractionInputModel input : inputs) {
                store.appendEvidence(new AgentMemoryEvidenceModel(
                        UUID.randomUUID().toString(), next.entryId(), "REQUEST", input.requestId(), now
                ));
            }
            persisted = true;
        }
        String outcome = persisted ? "success" : "skipped";
        observe(() -> observations.recordMemoryExtraction(outcome));
    }

    public List<AgentMemoryEntryModel> forReAct(
            String userId,
            String sessionId,
            AgentMemoryOptionsModel options
    ) {
        if (!shouldUse(options)) {
            return List.of();
        }
        List<AgentMemoryEntryModel> entries = usable(userId, sessionId, EnumSet.of(
                AgentMemoryCategoryEnum.PREFERENCE, AgentMemoryCategoryEnum.TASK_CONTEXT
        ), minimumAutoConfidence, 8, 4_000);
        observe(() -> observations.recordMemoryRetrieval(
                "react", entries.size(), entries.stream().mapToInt(entry -> entry.value().length()).sum()
        ));
        return entries;
    }

    public Optional<AgentMemoryEntryModel> workflowSuggestion(
            String userId,
            String sessionId,
            AgentMemoryOptionsModel options,
            String memoryKey
    ) {
        if (!shouldUse(options) || !TASK_CONTEXT_KEYS.contains(memoryKey)) {
            return Optional.empty();
        }
        Optional<AgentMemoryEntryModel> result = usable(userId, sessionId, EnumSet.of(AgentMemoryCategoryEnum.TASK_CONTEXT),
                0.90, 20, 8_000).stream()
                .filter(entry -> entry.memoryKey().equals(memoryKey))
                .findFirst();
        observe(() -> observations.recordMemoryRetrieval(
                "workflow", result.isPresent() ? 1 : 0, result.map(entry -> entry.value().length()).orElse(0)
        ));
        return result;
    }

    public AgentMemoryEntryModel create(
            String userId,
            String sessionId,
            AgentMemoryCategoryEnum category,
            String memoryKey,
            String value,
            Instant expiresAt
    ) {
        value = normalizeValue(category, memoryKey, value);
        validateManual(category, memoryKey, value, expiresAt);
        Instant now = clock.instant();
        Optional<AgentMemoryEntryModel> existing = store.findOwnedByKey(
                userId, sessionId, category.name(), memoryKey
        );
        AgentMemoryEntryModel entry = new AgentMemoryEntryModel(
                existing.map(AgentMemoryEntryModel::entryId).orElseGet(() -> UUID.randomUUID().toString()),
                existing.map(AgentMemoryEntryModel::sourceId).orElse(null), userId, sessionId,
                category, memoryKey, value, AgentMemoryOriginEnum.MANUAL, 1.0,
                existing.map(current -> current.version() + 1).orElse(0L), false,
                effectiveManualExpiry(category, expiresAt, now),
                existing.map(AgentMemoryEntryModel::createdAt).orElse(now), now
        );
        if (existing.isPresent()) {
            if (!store.update(existing.get(), entry)) {
                throw new AgentMemoryConflictException(entry.entryId());
            }
        } else {
            store.createEntry(entry);
        }
        return entry;
    }

    public List<AgentMemoryEntryModel> list(
            String userId, String sessionId, boolean includeDeleted, int limit
    ) {
        return store.list(userId, sessionId, includeDeleted, Math.min(Math.max(limit, 1), STORE_LIMIT));
    }

    public AgentMemoryEntryModel edit(
            String entryId,
            String userId,
            String sessionId,
            AgentMemoryCategoryEnum category,
            String memoryKey,
            String value,
            Instant expiresAt,
            long expectedVersion
    ) {
        String normalizedValue = normalizeValue(category, memoryKey, value);
        validateManual(category, memoryKey, normalizedValue, expiresAt);
        Instant now = clock.instant();
        AgentMemoryEntryModel entry = store.findOwned(entryId, userId, sessionId)
                .filter(current -> !current.deleted())
                .orElseThrow(() -> new AgentMemoryNotFoundException(entryId));
        if (entry.version() != expectedVersion) {
            throw new AgentMemoryConflictException(entryId);
        }
        AgentMemoryEntryModel updated = entry.edit(category, memoryKey, normalizedValue,
                effectiveManualExpiry(category, expiresAt, now), now);
        if (!store.update(entry, updated)) {
            throw new AgentMemoryConflictException(entryId);
        }
        return updated;
    }

    public void delete(String entryId, String userId, String sessionId, long expectedVersion) {
        AgentMemoryEntryModel entry = store.findOwned(entryId, userId, sessionId)
                .filter(current -> !current.deleted())
                .orElseThrow(() -> new AgentMemoryNotFoundException(entryId));
        if (entry.version() != expectedVersion || !store.update(entry, entry.delete(clock.instant()))) {
            throw new AgentMemoryConflictException(entryId);
        }
    }

    public List<AgentMemoryEvidenceModel> evidence(String entryId, String userId, String sessionId) {
        return store.listEvidence(entryId, userId, sessionId);
    }

    public boolean exists(String entryId, String userId, String sessionId) {
        return store.findOwned(entryId, userId, sessionId).isPresent();
    }

    public void recordExtractionOutcome(String outcome) {
        observe(() -> observations.recordMemoryExtraction(outcome));
    }

    private List<AgentMemoryEntryModel> usable(
            String userId,
            String sessionId,
            Set<AgentMemoryCategoryEnum> categories,
            double minimumConfidence,
            int limit,
            int maxCharacters
    ) {
        Instant now = clock.instant();
        int[] characters = {0};
        return store.list(userId, sessionId, false, STORE_LIMIT).stream()
                .filter(entry -> categories.contains(entry.category()))
                .filter(entry -> entry.origin() != AgentMemoryOriginEnum.LEGACY)
                .filter(entry -> entry.confidence() >= minimumConfidence)
                .filter(entry -> !entry.expiredAt(now))
                .sorted(Comparator.comparing(AgentMemoryEntryModel::updatedAt).reversed())
                .filter(entry -> {
                    if (characters[0] + entry.value().length() > maxCharacters) {
                        return false;
                    }
                    characters[0] += entry.value().length();
                    return true;
                })
                .limit(limit)
                .toList();
    }

    private boolean isAllowedKey(AgentMemoryCategoryEnum category, String memoryKey) {
        if (category == null || memoryKey == null) {
            return false;
        }
        return switch (category) {
            case PREFERENCE -> PREFERENCE_KEYS.contains(memoryKey);
            case TASK_CONTEXT -> TASK_CONTEXT_KEYS.contains(memoryKey);
            case LEGACY -> false;
        };
    }

    private void validateManual(
            AgentMemoryCategoryEnum category, String memoryKey, String value, Instant expiresAt
    ) {
        if (!isAllowedKey(category, memoryKey) || value == null || containsSensitiveContent(value)) {
            throw new IllegalArgumentException("memory key or value is not allowed");
        }
        if (expiresAt != null && !expiresAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("memory expiration must be in the future");
        }
    }

    private Instant expiry(AgentMemoryCategoryEnum category, Instant now) {
        return category == AgentMemoryCategoryEnum.TASK_CONTEXT ? now.plus(Duration.ofHours(24)) : null;
    }

    private Instant effectiveManualExpiry(
            AgentMemoryCategoryEnum category, Instant configuredExpiry, Instant now
    ) {
        return configuredExpiry == null ? expiry(category, now) : configuredExpiry;
    }

    private String normalizeValue(AgentMemoryCategoryEnum category, String memoryKey, String value) {
        if (category == null || memoryKey == null || value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (category == AgentMemoryCategoryEnum.PREFERENCE) {
            String lower = normalized.toLowerCase(Locale.ROOT);
            return switch (memoryKey) {
                case "response.language" -> switch (lower) {
                    case "zh", "zh-cn", "chinese", "中文", "简体中文" -> "zh-CN";
                    case "en", "en-us", "english", "英文" -> "en-US";
                    default -> null;
                };
                case "response.format" -> switch (lower) {
                    case "paragraph", "plain", "plain text", "prose", "段落", "纯文本" -> "paragraph";
                    case "markdown", "md" -> "markdown";
                    case "bullet_list", "bullets", "list", "列表", "要点" -> "bullet_list";
                    default -> null;
                };
                case "response.detail" -> switch (lower) {
                    case "concise", "brief", "简洁" -> "concise";
                    case "standard", "normal", "默认", "标准" -> "standard";
                    case "detailed", "detail", "详细" -> "detailed";
                    default -> null;
                };
                default -> null;
            };
        }
        if (category == AgentMemoryCategoryEnum.TASK_CONTEXT) {
            if ("order.id".equals(memoryKey)) {
                String orderId = normalized.toUpperCase(Locale.ROOT);
                return orderId.matches("ORDER-[A-Z0-9][A-Z0-9-]{0,62}") ? orderId : null;
            }
            if ("refund.reason".equals(memoryKey)) {
                try {
                    return cn.ethan.core.after_sales.enums.RefundReasonEnum.valueOf(
                            normalized.toUpperCase(Locale.ROOT)
                    ).name();
                } catch (IllegalArgumentException invalidReason) {
                    return null;
                }
            }
        }
        return null;
    }

    private boolean containsSensitiveContent(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return normalized.matches("(?s).*\\b(sk-[a-z0-9_-]{12,}|bearer\\s+[a-z0-9._-]{12,}|password\\s*[:=]).*")
                || normalized.matches("(?s).*\\b(?:\\d[ -]?){13,19}\\b.*");
    }

    private void observe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException observationFailure) {
            // 观测异常不能改变用户请求或后台任务结果。
        }
    }
}
