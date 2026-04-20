package cn.ethan.ai.domain.agent.model.valobj;

import lombok.Getter;

/**
 * 运行上下文窗口保护值对象，使用字符数进行保守估算，避免额外引入 tokenizer 依赖。
 */
@Getter
public class ContextWindowGuardVO {

    public static final int DEFAULT_MAX_CONTEXT_UNITS = 12000;

    private static final double COMPACT_HISTORY_THRESHOLD = 0.80;

    private static final double STOP_LLM_CALL_THRESHOLD = 0.95;

    private final int maxContextUnits;

    private int usedContextUnits;

    private boolean historyCompacted;

    public ContextWindowGuardVO() {
        this.maxContextUnits = DEFAULT_MAX_CONTEXT_UNITS;
    }

    public void record(String text) {
        int contextUnits = estimate(text);
        this.usedContextUnits += contextUnits;
    }

    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            if (Character.isWhitespace(codePoint)) {
                tokens += 0.25;
            } else if (isCjkCodePoint(codePoint)) {
                tokens += 1;
            } else if (codePoint <= 0x007F) {
                tokens += 0.25;
            } else {
                tokens += 0.5;
            }
            offset += Character.charCount(codePoint);
        }
        return Math.max(1, (int) Math.ceil(tokens));
    }

    public boolean shouldCompactHistory() {
        return !historyCompacted && usageRatio() >= COMPACT_HISTORY_THRESHOLD;
    }

    public boolean shouldStopNewLlmCall() {
        return usageRatio() >= STOP_LLM_CALL_THRESHOLD;
    }

    public void markHistoryCompacted() {
        this.historyCompacted = true;
    }

    public double usageRatio() {
        return maxContextUnits == 0 ? 1.0 : (double) usedContextUnits / maxContextUnits;
    }

    private boolean isCjkCodePoint(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
