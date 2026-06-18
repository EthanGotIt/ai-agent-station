package cn.ethan.ai.domain.agent.model.valobj;

import cn.ethan.ai.domain.agent.model.valobj.enums.AgentActionTypeEnumVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Harness 单轮受控动作。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentActionVO {

    private String actionId;

    private AgentActionTypeEnumVO type;

    private String query;

    private String reason;

    private String answer;

    @Builder.Default
    private Map<String, Object> args = new LinkedHashMap<>();

    private String rawText;

    private String parseError;

    public boolean hasParseError() {
        return parseError != null && !parseError.isBlank();
    }
}
