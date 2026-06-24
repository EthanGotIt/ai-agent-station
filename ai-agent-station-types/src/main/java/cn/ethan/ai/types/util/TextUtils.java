package cn.ethan.ai.types.util;

import java.util.Collection;
import java.util.Locale;

/**
 * Shared text utilities used across domain and infrastructure layers.
 */
public final class TextUtils {

    private TextUtils() {
    }

    /**
     * Truncate to maxLength, appending "..." when truncated.
     */
    public static String limit(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "...";
    }

    /**
     * Normalize whitespace then truncate.
     */
    public static String limitClean(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "...";
    }

    /**
     * Check whether {@code text} contains any of the {@code candidates} (case-sensitive).
     */
    public static boolean containsAny(String text, Collection<String> candidates) {
        if (text == null || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Trim whitespace and collapse multiple spaces into one.
     */
    public static String normalizeWhitespace(String content) {
        if (content == null) {
            return "";
        }
        return content.trim().replaceAll("\\s+", " ");
    }

    /**
     * Trim and lowercase — suitable for matching/comparing.
     */
    public static String normalizeForMatch(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }

}
