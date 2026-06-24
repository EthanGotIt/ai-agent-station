package cn.ethan.ai.domain.agent.service.execute.runtime;

import cn.ethan.ai.domain.agent.model.valobj.ContextUnitEstimator;
import cn.ethan.ai.domain.agent.model.valobj.HeuristicContextUnitEstimator;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 按业务优先级组装单次 Prompt。context-units 是近似预算，不是精确 token。
 */
@Service
public class PromptBudgetAssembler {

    private final int maxContextUnits;

    private final ContextUnitEstimator estimator;

    @Autowired
    public PromptBudgetAssembler(@Value("${ai-agent.context.max-context-units:12000}") int maxContextUnits) {
        this(maxContextUnits, HeuristicContextUnitEstimator.INSTANCE);
    }

    public PromptBudgetAssembler(int maxContextUnits, ContextUnitEstimator estimator) {
        this.maxContextUnits = maxContextUnits <= 0 ? 12000 : maxContextUnits;
        this.estimator = estimator == null ? HeuristicContextUnitEstimator.INSTANCE : estimator;
    }

    public String assemble(List<Section> sections) {
        List<Section> ordered = new ArrayList<>(sections == null ? List.of() : sections);
        ordered.sort(Comparator.comparingInt(Section::priority));
        StringBuilder result = new StringBuilder();
        for (Section section : ordered) {
            if (section == null || StringUtils.isBlank(section.content())) {
                continue;
            }
            String block = section.label() + "：\n" + section.content().trim() + "\n\n";
            if (estimator.estimate(result + block) <= maxContextUnits) {
                result.append(block);
                continue;
            }
            if (section.required()) {
                String fitted = fit(result.toString(), section.label() + "：\n", section.content());
                result.append(fitted);
            }
        }
        return result.toString().trim();
    }

    public int estimate(String prompt) {
        return estimator.estimate(prompt);
    }

    private String fit(String prefix, String label, String content) {
        int low = 0;
        int high = content.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            String candidate = prefix + label + content.substring(0, middle) + "...";
            if (estimator.estimate(candidate) <= maxContextUnits) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low == 0 ? "" : label + content.substring(0, low) + "...\n\n";
    }

    public record Section(int priority, String label, String content, boolean required) {

        public static Section required(int priority, String label, String content) {
            return new Section(priority, label, content, true);
        }

        public static Section optional(int priority, String label, String content) {
            return new Section(priority, label, content, false);
        }
    }
}
