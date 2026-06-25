package cn.ethan.ai.domain.agent.service.execute.springai;

import cn.ethan.ai.domain.agent.model.valobj.HeuristicContextUnitEstimator;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.stereotype.Component;

/**
 * 基于项目 HeuristicContextUnitEstimator 的 TokenCountEstimator 适配器。
 */
@Component
public class HeuristicTokenCountEstimator implements TokenCountEstimator {

    private static final int DEFAULT_MEDIA_ESTIMATE = 500;

    private final HeuristicContextUnitEstimator delegate;

    public HeuristicTokenCountEstimator() {
        this.delegate = HeuristicContextUnitEstimator.INSTANCE;
    }

    @Override
    public int estimate(String text) {
        return delegate.estimate(text);
    }

    @Override
    public int estimate(@NonNull MediaContent content) {
        String text = content.getText();
        if (text != null && !text.isEmpty()) {
            return delegate.estimate(text);
        }
        return DEFAULT_MEDIA_ESTIMATE;
    }

    @Override
    public int estimate(@NonNull Iterable<MediaContent> contents) {
        int total = 0;
        for (MediaContent content : contents) {
            total += estimate(content);
        }
        return total;
    }
}
