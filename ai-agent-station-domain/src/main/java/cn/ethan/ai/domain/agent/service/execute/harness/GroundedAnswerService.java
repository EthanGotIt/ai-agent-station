package cn.ethan.ai.domain.agent.service.execute.harness;

import cn.ethan.ai.domain.agent.adapter.port.IAgentModelPort;
import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.model.valobj.AgenticRagTraceVO;
import cn.ethan.ai.domain.agent.model.valobj.EvidenceBoardVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import cn.ethan.ai.domain.agent.service.execute.runtime.PromptBudgetAssembler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根据 Evidence Board 生成最终回答，并执行引用完整性校验。
 */
@Service
public class GroundedAnswerService {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[E(\\d+)]");

    private static final String EVIDENCE_REFUSAL = "当前证据不足，无法给出可核验的回答。";

    private final IAgentModelPort agentModelPort;

    private final EvidencePolicy evidencePolicy;

    private final EvidenceTraceAssembler traceAssembler;

    private final PromptBudgetAssembler budgetAssembler;

    public GroundedAnswerService(IAgentModelPort agentModelPort,
                                 EvidencePolicy evidencePolicy,
                                 EvidenceTraceAssembler traceAssembler,
                                 PromptBudgetAssembler budgetAssembler) {
        this.agentModelPort = agentModelPort;
        this.evidencePolicy = evidencePolicy;
        this.traceAssembler = traceAssembler;
        this.budgetAssembler = budgetAssembler;
    }

    public Result finalizeAnswer(AgentRunAggregate run,
                                 ExecuteCommandEntity command,
                                 AgentExecutionContextVO context,
                                 int streamStep) {
        EvidenceBoardVO board = context.getEvidenceBoard();
        EvidencePolicy.Decision policy = evidencePolicy.evaluateFinalization(
                command.getMessage(), board, context.getContextBoundary());
        AgenticRagTraceVO trace = traceAssembler.assemble(command.getMessage(), board, policy);
        if (!policy.allowed()) {
            return new Result(EVIDENCE_REFUSAL + " " + policy.reason(), trace, policy);
        }
        if (!policy.evidenceRequired()) {
            String prompt = "SESSION_CONTEXT".equals(policy.groundingMode())
                    ? buildSessionContextPrompt(command, context)
                    : buildModelOnlyPrompt(command);
            String answer = callModel(run, command, context, prompt, streamStep,
                    "SESSION_CONTEXT".equals(policy.groundingMode())
                            ? "harness_session_answer" : "harness_model_only_answer");
            return new Result(answer, trace, policy);
        }

        List<Document> documents = board.immutableEvidence();
        String answer = callModel(run, command, context, buildGroundedPrompt(command, documents), streamStep, "harness_grounded_answer");
        if (!citationsValid(answer, documents.size())) {
            answer = callModel(run, command, context, buildCitationRepairPrompt(command, documents, answer), streamStep,
                    "harness_citation_repair");
        }
        if (!citationsValid(answer, documents.size())) {
            return new Result(EVIDENCE_REFUSAL + " 回答引用校验未通过。", trace, EvidencePolicy.Decision.reject("引用校验失败"));
        }
        return new Result(answer, trace, policy);
    }

    private String callModel(AgentRunAggregate run,
                             ExecuteCommandEntity command,
                             AgentExecutionContextVO context,
                             String prompt,
                             int streamStep,
                             String eventType) {
        return agentModelPort.callModel(
                context.getAiAgentClientHarnessConfigVOMap(),
                command,
                run.getContextWindowGuard(),
                run.getTrace(),
                prompt,
                eventType,
                eventType,
                streamStep,
                ToolRoutingDecisionVO.disabled("最终回答阶段不注入 MCP 工具。"),
                AiClientTypeEnumVO.RESPONSE_ASSISTANT,
                AiClientTypeEnumVO.EXECUTOR_CLIENT,
                AiClientTypeEnumVO.DEFAULT
        );
    }

    private String buildModelOnlyPrompt(ExecuteCommandEntity command) {
        return "请直接完成下列非事实检索型任务，不要声称使用了外部证据。\n\n用户输入：\n"
                + StringUtils.defaultString(command.getMessage());
    }

    private String buildSessionContextPrompt(ExecuteCommandEntity command, AgentExecutionContextVO context) {
        String instruction = """
                请仅根据同一 Session 的历史完整 Turn、结构化摘要和回答偏好回答。
                不得引入 Session 中未出现的外部事实，不得声称执行了新检索。
                历史回答中的 [E1]、[E2] 等编号只属于原 Run，不得在当前回答中复制或生成 Evidence 引用。
                """;
        return budgetAssembler.assemble(List.of(
                PromptBudgetAssembler.Section.required(0, "Session 回答规则", instruction),
                PromptBudgetAssembler.Section.required(10, "当前问题", StringUtils.defaultString(command.getMessage())),
                PromptBudgetAssembler.Section.required(20, "Session 上下文",
                        StringUtils.defaultString(context.getContextBoundary().getSessionContextSummary())),
                PromptBudgetAssembler.Section.optional(30, "回答偏好",
                        String.valueOf(context.getContextBoundary().getUserPreferences()))
        ));
    }

    private String buildGroundedPrompt(ExecuteCommandEntity command, List<Document> documents) {
        String instruction = """
                请只基于 Evidence Board 回答问题。
                每个事实结论必须使用 [E1]、[E2] 形式引用，引用编号必须存在。
                证据有冲突或缺口时必须明确说明，不得使用模型记忆补齐。
                """;
        return budgetAssembler.assemble(List.of(
                PromptBudgetAssembler.Section.required(0, "回答规则", instruction),
                PromptBudgetAssembler.Section.required(10, "当前问题", StringUtils.defaultString(command.getMessage())),
                PromptBudgetAssembler.Section.required(20, "Evidence Board", formatEvidence(documents))
        ));
    }

    private String buildCitationRepairPrompt(ExecuteCommandEntity command, List<Document> documents, String answer) {
        return """
                下面的回答未通过引用校验。请只修正引用与不受证据支持的表述，不得引入新事实。
                输出完整修正答案，每个事实结论使用存在的 [E1]、[E2] 引用。

                用户问题：%s
                Evidence Board：
                %s

                待修正回答：
                %s
                """.formatted(StringUtils.defaultString(command.getMessage()), formatEvidence(documents), StringUtils.defaultString(answer));
    }

    private String formatEvidence(List<Document> documents) {
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            lines.add("[E" + (index + 1) + "] title=" + metadata(document, "title", "source")
                    + " uri=" + metadata(document, "uri", "url")
                    + " content=" + limit(document.getText(), 1800));
        }
        return String.join(System.lineSeparator(), lines);
    }

    private boolean citationsValid(String answer, int evidenceCount) {
        if (StringUtils.isBlank(answer) || evidenceCount <= 0) {
            return false;
        }
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        Set<Integer> citations = new HashSet<>();
        while (matcher.find()) {
            int id = Integer.parseInt(matcher.group(1));
            if (id < 1 || id > evidenceCount) {
                return false;
            }
            citations.add(id);
        }
        return !citations.isEmpty();
    }

    private String metadata(Document document, String... keys) {
        if (document == null || document.getMetadata() == null) {
            return "";
        }
        for (String key : keys) {
            Object value = document.getMetadata().get(key);
            if (value != null && StringUtils.isNotBlank(value.toString())) {
                return value.toString();
            }
        }
        return "";
    }

    private String limit(String value, int maxLength) {
        String actual = StringUtils.defaultString(value).trim();
        return actual.length() <= maxLength ? actual : actual.substring(0, maxLength) + "...";
    }

    public record Result(String answer, AgenticRagTraceVO trace, EvidencePolicy.Decision policy) {
    }
}
