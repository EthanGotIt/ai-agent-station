package cn.ethan.ai.domain.agent.model.valobj;

import cn.ethan.ai.domain.agent.model.valobj.enums.McpClientLifecycleStatusEnumVO;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 单个 MCP 客户端的安全运行态快照。
 */
@Value
@Builder
public class McpClientStateVO {

    String mcpId;

    String mcpName;

    McpClientLifecycleStatusEnumVO status;

    int initializationAttempts;

    int toolCount;

    long lastInitializationMillis;

    LocalDateTime lastFailureAt;

    String lastError;
}
