package cn.ethan.ai.types.common;

/**
 * Default context budget values — single source of truth for all context-window policies.
 */
public final class ContextBudgetConstants {

    private ContextBudgetConstants() {
    }

    /** Maximum context units for session context assembly. */
    public static final int DEFAULT_MAX_CONTEXT_UNITS = 2400;

    /** Threshold ratio at which history compaction triggers. */
    public static final double DEFAULT_COMPACT_THRESHOLD = 0.80D;

    /** Number of most recent messages kept as original text. */
    public static final int DEFAULT_KEEP_RECENT_MESSAGES = 4;

    /** Max chars for a single message summary. */
    public static final int DEFAULT_MESSAGE_SUMMARY_MAX_CHARS = 240;

    /** Max recent messages loaded from DB. */
    public static final int DEFAULT_RECENT_MESSAGE_LIMIT = 20;

    /** Max context units for the context window guard. */
    public static final int DEFAULT_MAX_CONTEXT_CHARS = 12000;

    /** Summary max chars used by context window service. */
    public static final int DEFAULT_SUMMARY_MAX_CHARS = 1500;

    /** Stop LLM call threshold ratio. */
    public static final double DEFAULT_STOP_THRESHOLD = 0.95D;

    /** Maximum context units sent in armory assembly context. */
    public static final int ASSEMBLY_MAX_CONTEXT_UNITS = 2400;

}
