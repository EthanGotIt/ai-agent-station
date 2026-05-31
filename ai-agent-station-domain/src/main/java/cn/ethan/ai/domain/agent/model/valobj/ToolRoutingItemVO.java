package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个 MCP 工具路由项。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolRoutingItemVO {

    private String mcpId;

    private String mcpName;

    private String transportType;

    private List<String> toolNames;

    private List<String> routeTags;

    private String riskLevel;

    private List<String> blockedToolNames;

    private String guardReason;

    private String selectedReason;
}

