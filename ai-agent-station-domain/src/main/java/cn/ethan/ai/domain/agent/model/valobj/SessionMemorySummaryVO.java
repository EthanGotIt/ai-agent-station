package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只保存会话目标和交互约束，不保存工具输出、外部事实或模型猜测。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMemorySummaryVO {

    @Builder.Default
    private List<String> goals = new ArrayList<>();

    @Builder.Default
    private List<String> constraints = new ArrayList<>();

    @Builder.Default
    private List<String> confirmedDecisions = new ArrayList<>();

    @Builder.Default
    private List<String> unresolvedQuestions = new ArrayList<>();

    @Builder.Default
    private Map<String, String> responsePreferences = new LinkedHashMap<>();

    @JsonIgnore
    public boolean isEmpty() {
        return goals.isEmpty() && constraints.isEmpty() && confirmedDecisions.isEmpty()
                && unresolvedQuestions.isEmpty() && responsePreferences.isEmpty();
    }
}
