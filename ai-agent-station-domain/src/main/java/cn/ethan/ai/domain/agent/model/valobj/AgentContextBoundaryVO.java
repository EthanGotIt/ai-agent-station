package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * Agent 单次运行的上下文边界。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContextBoundaryVO {

    private String sessionId;

    private String projectRuleScope;

    private String userPreferenceScope;

    private String conversationScope;

    @Builder.Default
    private List<String> projectRules = Collections.emptyList();

    @Builder.Default
    private List<String> userPreferences = Collections.emptyList();

    private String runContextSummary;

    @Builder.Default
    private boolean longTermMemoryEnabled = false;

}
