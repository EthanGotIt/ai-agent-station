package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单次运行的 MCP 动态工具路由结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolRoutingDecisionVO {

    private boolean enabled;

    private String summary;

    @Builder.Default
    private Set<String> allowedToolNames = new LinkedHashSet<>();

    @Builder.Default
    private Set<String> selectedMcpIds = new LinkedHashSet<>();

    @Builder.Default
    private List<ToolRoutingItemVO> selectedTools = new ArrayList<>();

    @Builder.Default
    private Set<String> blockedToolNames = new LinkedHashSet<>();

    @Builder.Default
    private Map<String, String> blockedToolReasons = new LinkedHashMap<>();

    public static ToolRoutingDecisionVO disabled(String summary) {
        return ToolRoutingDecisionVO.builder()
                .enabled(false)
                .summary(summary)
                .build();
    }

    public static ToolRoutingDecisionVO disabled(String summary,
                                                 Set<String> blockedToolNames,
                                                 Map<String, String> blockedToolReasons) {
        return ToolRoutingDecisionVO.builder()
                .enabled(false)
                .summary(summary)
                .blockedToolNames(blockedToolNames == null ? new LinkedHashSet<>() : blockedToolNames)
                .blockedToolReasons(blockedToolReasons == null ? new LinkedHashMap<>() : blockedToolReasons)
                .build();
    }

}

