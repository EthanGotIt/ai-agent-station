package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单次真实 ToolCallback 调用记录。只在当前 Run 内用于证据归一化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvocationRecordVO {

    private String toolName;

    private String inputPreview;

    private boolean success;

    private String output;

    private String errorType;

    private long costMillis;
}
