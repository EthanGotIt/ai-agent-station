package cn.ethan.ai.domain.agent.model.valobj.enums;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

/**
 * 受控 Harness 动作类型。
 */
public enum AgentActionTypeEnumVO {

    RETRIEVE,
    ASK_CLARIFY,
    FINALIZE;

    public static AgentActionTypeEnumVO from(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return AgentActionTypeEnumVO.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
