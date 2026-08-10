package cn.ethan.core.agent.model;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ReAct 结果模型：统一承载最终答案、来源和模型用量。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record ReActResultModel(
        String answer,
        List<String> sources,
        int inputTokens,
        int outputTokens
) {

    private static final int MAX_SOURCES = 20;
    private static final int MAX_SOURCE_LENGTH = 2_048;
    public ReActResultModel {
        answer = answer == null || answer.isBlank() ? "模型没有返回内容。" : answer;
        sources = normalizeSources(sources);
        inputTokens = Math.max(inputTokens, 0);
        outputTokens = Math.max(outputTokens, 0);
    }

    public static ReActResultModel answer(String answer) {
        return new ReActResultModel(answer, List.of(), 0, 0);
    }

    /**
     * 将结构化来源附加到最终答案，供同步与流式接口复用同一最终内容。
     *
     * @return 不含模型推理过程的最终用户内容
     */
    public String finalContent() {
        return answer + sourceSuffix();
    }

    public String sourceSuffix() {
        if (sources.isEmpty()) {
            return "";
        }

        StringBuilder content = new StringBuilder("\n\n来源：");
        for (String source : sources) {
            if (!answer.contains(source)) {
                content.append("\n- ").append(source);
            }
        }
        return content.length() == "\n\n来源：".length() ? "" : content.toString();
    }

    private static List<String> normalizeSources(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (normalized.size() >= MAX_SOURCES) {
                break;
            }
            if (value == null) {
                continue;
            }
            String source = value.strip();
            if (source.isEmpty() || source.length() > MAX_SOURCE_LENGTH) {
                continue;
            }
            try {
                URI uri = URI.create(source);
                String scheme = uri.getScheme();
                boolean webScheme = "http".equalsIgnoreCase(scheme)
                        || "https".equalsIgnoreCase(scheme);
                if (webScheme
                        && uri.getRawAuthority() != null
                        && uri.getRawUserInfo() == null) {
                    normalized.add(uri.toASCIIString());
                }
            } catch (IllegalArgumentException invalidUri) {
                // 模型或工具返回的非法 URI 不是业务故障，直接丢弃该来源
            }
        }
        return List.copyOf(normalized);
    }

}
