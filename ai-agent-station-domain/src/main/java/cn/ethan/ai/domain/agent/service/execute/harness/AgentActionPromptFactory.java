package cn.ethan.ai.domain.agent.service.execute.harness;

import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentContextBoundaryVO;
import cn.ethan.ai.domain.agent.model.valobj.HarnessObservationVO;
import cn.ethan.ai.domain.agent.model.valobj.EvidenceBoardVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import cn.ethan.ai.domain.agent.service.execute.runtime.PromptBudgetAssembler;

import java.util.ArrayList;
import java.util.List;

/**
 * Harness 决策提示词。
 */
@Service
public class AgentActionPromptFactory {

    private final PromptBudgetAssembler budgetAssembler;

    public AgentActionPromptFactory(PromptBudgetAssembler budgetAssembler) {
        this.budgetAssembler = budgetAssembler;
    }

    public String buildActionPrompt(ExecuteCommandEntity command,
                                    AgentContextBoundaryVO contextBoundary,
                                    List<HarnessObservationVO> observations,
                                    EvidenceBoardVO evidenceBoard,
                                    int round,
                                    int maxRounds) {
        String instructions = """
                你是 AI Agent Station 的 Controlled Agent Harness 决策器。
                你必须只输出一个 JSON 对象，不要输出 Markdown，不要解释 JSON 外的内容。

                可选 actionType：
                - RETRIEVE：从一个受控来源检索 evidence，检索本身不会直接结束任务。
                - ASK_CLARIFY：用户问题缺少必要约束，需要追问。
                - FINALIZE：已有足够 evidence，或任务无需事实依据，由后端生成最终回答。

                输出 JSON 格式：
                {
                  "actionType": "RETRIEVE | ASK_CLARIFY | FINALIZE",
                  "sourceType": "PROJECT_KNOWLEDGE | OFFICIAL_DOCS | WEB_RESEARCH，仅 RETRIEVE 填写",
                  "queries": ["最多两个检索问题，仅 RETRIEVE 填写"],
                  "reason": "选择该动作的简短原因",
                  "clarifyingQuestion": "仅 ASK_CLARIFY 填写",
                  "assessment": {
                    "sufficient": false,
                    "coverage": 0.0,
                    "confidence": 0.0,
                    "gaps": ["NO_RELEVANT_EVIDENCE | CONTEXT_INCOMPLETE | MISSING_SOURCE | CONFLICT"],
                    "reason": "对当前 evidence 的判断"
                  }
                }

                决策边界：
                - 不要生成固定流程或多步骤计划。
                - PROJECT_KNOWLEDGE 用于当前项目内部知识。
                - 问题包含“项目/当前实现/本系统”并询问设计原因、类、配置或内部行为时，优先选择 PROJECT_KNOWLEDGE。
                - OFFICIAL_DOCS 用于框架、类库、协议、云厂商的 API 与版本化官方说明，即使这些资料可能更新也优先选择官方来源。
                - WEB_RESEARCH 仅用于新闻、市场现状、跨站对比，或明确不存在单一官方来源的问题，不要用它替代可获得的官方文档。
                - 比较当前项目与外部规范、框架或实践时，必须分别获取 PROJECT_KNOWLEDGE 和 OFFICIAL_DOCS/WEB_RESEARCH，再决定 FINALIZE。
                - 你只能选择高层来源，不能指定具体检索实现或 MCP 工具。
                - evidence 不足时可以改写 query，但禁止重复相同来源和相同 query。
                - FINALIZE 不能携带答案，最终回答由后端统一生成。
                - 工具失败或证据不足时不得编造结果。

                当前轮次：%d/%d
                """.formatted(round, maxRounds);
        List<PromptBudgetAssembler.Section> sections = new ArrayList<>();
        sections.add(PromptBudgetAssembler.Section.required(0, "决策协议", instructions));
        sections.add(PromptBudgetAssembler.Section.required(10, "当前问题", StringUtils.defaultString(command.getMessage())));
        sections.add(PromptBudgetAssembler.Section.required(20, "项目规则", formatProjectRules(contextBoundary)));
        sections.add(PromptBudgetAssembler.Section.required(30, "Evidence Board",
                evidenceBoard == null ? "当前没有可归因 evidence。" : evidenceBoard.compactObservation()));
        sections.add(PromptBudgetAssembler.Section.optional(40, "Session 上下文", formatSessionContext(contextBoundary)));
        sections.add(PromptBudgetAssembler.Section.optional(50, "Harness observation", formatObservations(observations)));
        return budgetAssembler.assemble(sections);
    }

    public String buildDirectResponsePrompt(ExecuteCommandEntity command, List<HarnessObservationVO> observations) {
        return """
                请基于用户输入和已有 observation 给出简洁、可验证的回答。
                如果 observation 表明证据不足，必须明确说明不能从现有证据确认，不要编造。

                用户输入：
                %s

                observation：
                %s
                """.formatted(StringUtils.defaultString(command.getMessage()), formatObservations(observations));
    }

    private String formatProjectRules(AgentContextBoundaryVO contextBoundary) {
        if (contextBoundary == null) {
            return "不得编造 evidence，不得跨 session 使用上下文。";
        }
        return String.valueOf(contextBoundary.getProjectRules());
    }

    private String formatSessionContext(AgentContextBoundaryVO contextBoundary) {
        if (contextBoundary == null) {
            return "无。";
        }
        return "sessionId=" + StringUtils.defaultString(contextBoundary.getSessionId())
                + "，preferences=" + contextBoundary.getUserPreferences()
                + "，memory=" + StringUtils.defaultString(contextBoundary.getSessionContextSummary());
    }

    private String formatObservations(List<HarnessObservationVO> observations) {
        if (observations == null || observations.isEmpty()) {
            return "暂无。";
        }
        StringBuilder builder = new StringBuilder();
        for (HarnessObservationVO observation : observations) {
            builder.append("- ")
                    .append(observation.getActionType())
                    .append("：")
                    .append(StringUtils.defaultString(observation.getMessage()))
                    .append(System.lineSeparator());
        }
        return builder.toString().trim();
    }
}
