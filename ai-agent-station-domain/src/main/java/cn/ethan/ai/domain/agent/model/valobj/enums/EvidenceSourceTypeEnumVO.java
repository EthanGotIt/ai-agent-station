package cn.ethan.ai.domain.agent.model.valobj.enums;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

/**
 * Harness 可选择的高层证据来源。底层向量、关键词和融合策略不暴露给模型。
 */
public enum EvidenceSourceTypeEnumVO {

    PROJECT_KNOWLEDGE,
    OFFICIAL_DOCS,
    WEB_RESEARCH;

    public static EvidenceSourceTypeEnumVO from(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isExternal() {
        return this != PROJECT_KNOWLEDGE;
    }
}
