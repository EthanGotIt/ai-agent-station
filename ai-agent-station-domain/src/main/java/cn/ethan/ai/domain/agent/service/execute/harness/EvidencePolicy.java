package cn.ethan.ai.domain.agent.service.execute.harness;

import cn.ethan.ai.domain.agent.model.valobj.EvidenceBoardVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentContextBoundaryVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.EvidenceSourceTypeEnumVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 对模型的 evidence assessment 执行确定性复核。
 */
@Service
public class EvidencePolicy {

    private static final List<String> MODEL_ONLY_HINTS = List.of(
            "润色", "改写", "翻译", "生成文案", "写一段", "格式偏好", "后续请", "以后请"
    );

    private static final List<String> SESSION_FOLLOW_UP_HINTS = List.of(
            "上一轮", "刚才", "之前", "前面", "继续", "再回答", "你提到", "刚提到"
    );

    private static final List<String> PROJECT_HINTS = List.of("项目", "当前", "本系统", "我们的");

    private static final List<String> COMPARISON_HINTS = List.of(
            "对比", "比较", "区别", "差异", "异同", "共同点", "是否符合", "是否匹配", "是否一致", "是否覆盖", "分别解决"
    );

    public Decision evaluateFinalization(String question, EvidenceBoardVO board) {
        return evaluateFinalization(question, board, null);
    }

    public Decision evaluateFinalization(String question,
                                         EvidenceBoardVO board,
                                         AgentContextBoundaryVO contextBoundary) {
        if (!requiresEvidence(question)) {
            return Decision.allow(false, "当前任务允许模型直接生成。", "MODEL_ONLY");
        }
        if (isSessionFollowUp(question) && hasSessionContext(contextBoundary)) {
            return Decision.allow(false, "当前问题可由同一 Session 的成功完整 Turn 回答。", "SESSION_CONTEXT");
        }
        if (board == null || !board.hasEvidence()) {
            return Decision.reject("当前问题需要事实依据，但 Evidence Board 为空。");
        }
        boolean attributable = board.getEvidences().stream().anyMatch(this::isAttributable);
        if (!attributable) {
            return Decision.reject("现有内容无法归因到项目知识或可验证来源。");
        }
        String normalized = StringUtils.defaultString(question).toLowerCase();
        if (containsAny(normalized, "官方", "官网", "版本", "最新")) {
            boolean hasExpectedSource = board.getEvidences().stream().anyMatch(document -> {
                String source = metadata(document, "qa_evidence_source_type");
                return EvidenceSourceTypeEnumVO.OFFICIAL_DOCS.name().equals(source)
                        || EvidenceSourceTypeEnumVO.WEB_RESEARCH.name().equals(source);
            });
            if (!hasExpectedSource) {
                return Decision.reject("版本化或最新问题缺少官方文档/外部资料来源。");
            }
        }
        if (requiresProjectExternalComparison(normalized)) {
            Set<String> sources = board.getEvidences().stream()
                    .map(document -> metadata(document, "qa_evidence_source_type"))
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toSet());
            boolean hasProject = sources.contains(EvidenceSourceTypeEnumVO.PROJECT_KNOWLEDGE.name());
            boolean hasExternal = sources.contains(EvidenceSourceTypeEnumVO.OFFICIAL_DOCS.name())
                    || sources.contains(EvidenceSourceTypeEnumVO.WEB_RESEARCH.name());
            if (!hasProject || !hasExternal) {
                return Decision.reject("比较当前项目与外部规范时，必须同时具备 PROJECT_KNOWLEDGE 和官方/外部 evidence。");
            }
        }
        return Decision.allow(true, "Evidence Policy 校验通过。", "EVIDENCE_REQUIRED");
    }

    public boolean requiresEvidence(String question) {
        String normalized = StringUtils.defaultString(question).toLowerCase();
        return MODEL_ONLY_HINTS.stream().noneMatch(normalized::contains);
    }

    private boolean isSessionFollowUp(String question) {
        String normalized = StringUtils.defaultString(question).toLowerCase();
        return SESSION_FOLLOW_UP_HINTS.stream().anyMatch(normalized::contains);
    }

    private boolean hasSessionContext(AgentContextBoundaryVO contextBoundary) {
        return contextBoundary != null
                && StringUtils.isNotBlank(contextBoundary.getSessionContextSummary());
    }

    private boolean requiresProjectExternalComparison(String question) {
        boolean projectQuestion = PROJECT_HINTS.stream().anyMatch(question::contains);
        boolean comparisonQuestion = COMPARISON_HINTS.stream().anyMatch(question::contains);
        return projectQuestion && comparisonQuestion;
    }

    private boolean isAttributable(Document document) {
        Object value = document == null ? null : document.getMetadata().get("qa_evidence_attributable");
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String metadata(Document document, String key) {
        Object value = document == null ? null : document.getMetadata().get(key);
        return value == null ? "" : value.toString();
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    public record Decision(boolean allowed, boolean evidenceRequired, String reason, String groundingMode) {

        public static Decision allow(boolean evidenceRequired, String reason, String groundingMode) {
            return new Decision(true, evidenceRequired, reason, groundingMode);
        }

        public static Decision reject(String reason) {
            return new Decision(false, true, reason, "REFUSE");
        }
    }
}
