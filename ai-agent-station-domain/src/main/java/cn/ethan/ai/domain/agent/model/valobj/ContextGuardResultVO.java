package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Map;

/**
 * 上下文保护处理结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextGuardResultVO {

    @Builder.Default
    private Map<String, String> stepOutputs = Collections.emptyMap();

    private boolean compressed;

    private int originalChars;

    private int compressedChars;

    private String historySummary;

}
