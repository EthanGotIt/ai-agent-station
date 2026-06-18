package cn.ethan.ai.domain.agent.service.execute.harness;

import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentContextBoundaryVO;
import cn.ethan.ai.domain.agent.model.valobj.HarnessObservationVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Harness 决策提示词。
 */
@Service
public class AgentActionPromptFactory {

    public String buildActionPrompt(ExecuteCommandEntity command,
                                    AgentContextBoundaryVO contextBoundary,
                                    ToolRoutingDecisionVO toolRoutingDecision,
                                    List<HarnessObservationVO> observations,
                                    int round,
                                    int maxRounds) {
        return """
                你是 AI Agent Station 的 Controlled Agent Harness 决策器。
                你必须只输出一个 JSON 对象，不要输出 Markdown，不要解释 JSON 外的内容。

                可选 actionType：
                - RAG_RETRIEVE：需要基于企业知识库、技术资料或外部只读 evidence 回答。
                - MCP_READ：需要读取已授权的只读外部资料，但还不进入本地知识库检索。
                - LLM_RESPOND：无需外部证据，直接用模型能力回答。
                - ASK_CLARIFY：用户问题缺少必要约束，需要追问。
                - FINAL：已有足够 observation，可以给出最终回答。

                输出 JSON 格式：
                {
                  "actionType": "RAG_RETRIEVE | MCP_READ | LLM_RESPOND | ASK_CLARIFY | FINAL",
                  "query": "本轮要处理的问题或检索问题",
                  "reason": "选择该动作的简短原因",
                  "answer": "仅 FINAL 或 ASK_CLARIFY 时填写"
                }

                决策边界：
                - 不要生成固定流程或多步骤计划。
                - 如果问题涉及知识库问答、资料调研、官方文档、最新资料、事实核验，优先选择 RAG_RETRIEVE。
                - MCP 工具只由运行时按权限注入，你不能指定未授权工具。
                - RAG 内部最多允许一次二次检索，禁止无限循环。
                - 工具失败或证据不足时不得编造结果。

                当前轮次：%d/%d
                用户输入：
                %s

                上下文边界：
                %s

                本轮工具路由：
                %s

                已有 observation：
                %s
                """.formatted(
                round,
                maxRounds,
                StringUtils.defaultString(command.getMessage()),
                formatBoundary(contextBoundary),
                toolRoutingDecision == null ? "未启用 MCP 工具。" : StringUtils.defaultString(toolRoutingDecision.getSummary()),
                formatObservations(observations)
        );
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

    private String formatBoundary(AgentContextBoundaryVO contextBoundary) {
        if (contextBoundary == null) {
            return "无已加载 session 上下文。";
        }
        return "sessionId=" + StringUtils.defaultString(contextBoundary.getSessionId())
                + "，longTermMemoryEnabled=" + contextBoundary.isLongTermMemoryEnabled()
                + "，contextSummary=" + StringUtils.defaultString(contextBoundary.getSessionContextSummary());
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
