package cn.ethan.ai.domain.agent.model.valobj;

/**
 * 默认上下文预算估算器：中文更保守，英文按近似 token 比例估算。
 */
public class HeuristicContextUnitEstimator implements ContextUnitEstimator {

    public static final HeuristicContextUnitEstimator INSTANCE = new HeuristicContextUnitEstimator();

    @Override
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

    private boolean isCjkCodePoint(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
