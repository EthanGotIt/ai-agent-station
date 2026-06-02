package cn.ethan.ai.domain.agent.service.execute.graph;

import cn.ethan.ai.domain.agent.model.valobj.enums.ToolRiskLevelEnumVO;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 工具治理策略：按工具名做轻量风险分级与危险工具拦截。
 */
public final class ToolGuardPolicy {

    private static final List<String> DANGEROUS_HINTS = List.of(
            "delete", "remove", "drop", "truncate", "shutdown", "kill",
            "exec", "execute", "shell", "terminal", "powershell", "cmd",
            "bash", "system", "rm_", "write_file", "file_write", "filesystem"
    );

    private static final List<String> HIGH_RISK_HINTS = List.of(
            "write", "create", "update", "modify", "patch", "upload",
            "send", "notify", "reminder", "relation", "observation"
    );

    private static final List<String> LOW_RISK_HINTS = List.of(
            "search", "fetch", "read", "get", "list", "resolve", "docs", "open"
    );

    private ToolGuardPolicy() {
    }

    public static ToolRiskLevelEnumVO assessRisk(String toolName) {
        String normalizedName = normalize(toolName);
        if (StringUtils.isBlank(normalizedName)) {
            return ToolRiskLevelEnumVO.HIGH;
        }
        if (containsAny(normalizedName, DANGEROUS_HINTS)) {
            return ToolRiskLevelEnumVO.DANGEROUS;
        }
        if (containsAny(normalizedName, HIGH_RISK_HINTS)) {
            return ToolRiskLevelEnumVO.HIGH;
        }
        if (containsAny(normalizedName, LOW_RISK_HINTS)) {
            return ToolRiskLevelEnumVO.LOW;
        }
        return ToolRiskLevelEnumVO.MEDIUM;
    }

    public static boolean isBlocked(String toolName) {
        return ToolRiskLevelEnumVO.DANGEROUS == assessRisk(toolName);
    }

    public static String describe(String toolName) {
        ToolRiskLevelEnumVO riskLevel = assessRisk(toolName);
        if (ToolRiskLevelEnumVO.DANGEROUS == riskLevel) {
            return "Tool Guard 拒绝危险工具：" + StringUtils.defaultString(toolName);
        }
        return "Tool Guard 风险等级：" + riskLevel;
    }

    public static String normalize(String toolName) {
        return StringUtils.defaultString(toolName).trim().toLowerCase(Locale.ROOT);
    }

    public static ToolRiskLevelEnumVO max(ToolRiskLevelEnumVO left, ToolRiskLevelEnumVO right) {
        if (left == null) {
            return right == null ? ToolRiskLevelEnumVO.MEDIUM : right;
        }
        if (right == null) {
            return left;
        }
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private static boolean containsAny(String text, List<String> hints) {
        for (String hint : hints) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
