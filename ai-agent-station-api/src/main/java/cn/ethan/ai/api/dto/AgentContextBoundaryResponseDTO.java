package cn.ethan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContextBoundaryResponseDTO {

    private String sessionId;

    private String projectRuleScope;

    private String userPreferenceScope;

    private String conversationScope;

    @Builder.Default
    private List<String> projectRules = Collections.emptyList();

    @Builder.Default
    private List<String> userPreferences = Collections.emptyList();

    private String sessionContextSummary;

    private boolean longTermMemoryEnabled;

}
