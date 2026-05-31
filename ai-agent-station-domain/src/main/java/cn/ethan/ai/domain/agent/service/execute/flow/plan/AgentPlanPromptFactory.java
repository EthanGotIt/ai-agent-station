package cn.ethan.ai.domain.agent.service.execute.flow.plan;

import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentContextBoundaryVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanStepVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanVO;
import cn.ethan.ai.domain.agent.service.execute.flow.AgentContextBoundaryService;
import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Flow Plan 提示词工厂
 */
@Service
public class AgentPlanPromptFactory {

    public String buildPlanningPrompt(ExecuteCommandEntity command, String toolCapabilitySummary) {
        return buildPlanningPrompt(command, toolCapabilitySummary, null);
    }

    public String buildPlanningPrompt(ExecuteCommandEntity command,
                                      String toolCapabilitySummary,
                                      AgentContextBoundaryVO contextBoundary) {
        int maxStep = command.getMaxStep() == null || command.getMaxStep() <= 0 ? 5 : command.getMaxStep();
        return """
                你是 Flow Plan Engine 的计划生成器。请只输出合法 JSON，不要输出 Markdown 或额外解释。
                后端会校验并执行该计划，所以步骤必须清晰、可执行、依赖关系明确。

                必须使用以下 JSON 结构：
                {
                  "goal": "用户目标",
                  "steps": [
                    {
                      "stepId": "step_1",
                      "name": "简短步骤名称",
                      "type": "LLM|RAG|TOOL|SUPERVISION|SUMMARY",
                      "input": {},
                      "dependsOn": [],
                      "successCriteria": "清晰的完成标准"
                    }
                  ]
                }

                规则：
                1. 最多生成 %d 个步骤。
                2. dependsOn 只能引用更早出现的 stepId。
                3. 需要基于已导入知识库、内部资料或证据回答时，使用 type=RAG。
                4. 不需要知识库或精确工具时，使用 type=LLM。
                5. 不要在计划阶段提前绑定具体 MCP 工具；工具会在执行阶段由系统按权限筛选并注入。
                6. Plan 只描述步骤类型和依赖关系，不输出 toolName 字段。
                7. 必须遵守上下文治理边界，不得跨 session 复用用户偏好或会话历史。

                %s

                执行阶段工具策略摘要：
                %s

                用户请求：
                %s
                """.formatted(
                maxStep,
                AgentContextBoundaryService.buildPromptSection(contextBoundary),
                toolCapabilitySummary,
                command.getMessage()
        );
    }

    public String buildPlanRepairPrompt(String planText) {
        return """
                请把下面的模型输出修复为合法 JSON，只输出 JSON 本身。
                必须符合结构：
                {"goal":"...","steps":[{"stepId":"step_1","name":"...","type":"LLM|RAG|TOOL|SUPERVISION|SUMMARY","input":{},"dependsOn":[],"successCriteria":"..."}]}
                不要输出 Markdown 代码块，也不要输出解释。

                原始输出：
                %s
                """.formatted(planText);
    }

    public String buildStepExecutionPrompt(ExecuteCommandEntity command,
                                           AgentPlanVO plan,
                                           AgentPlanStepVO step,
                                           Map<String, String> stepOutputs) {
        return buildStepExecutionPrompt(command, plan, step, stepOutputs, null);
    }

    public String buildStepExecutionPrompt(ExecuteCommandEntity command,
                                           AgentPlanVO plan,
                                           AgentPlanStepVO step,
                                           Map<String, String> stepOutputs,
                                           AgentContextBoundaryVO contextBoundary) {
        return """
                你是 Flow Plan Engine 的步骤执行器。
                请只执行当前已校验步骤；如果步骤 type=RAG，请优先基于已导入知识库证据回答，并保留证据可追踪性。
                当前可用 MCP 工具已经由系统按权限筛选并注入；如果工具有助于完成当前步骤，可以调用工具；不需要工具时直接完成。
                如果工具不可用、参数错误或调用失败，不要编造工具结果，应说明失败原因，并在不依赖该工具的情况下给出可行替代结果。
                执行时必须遵守上下文治理边界。

                %s

                用户原始请求：
                %s

                计划目标：
                %s

                当前步骤：
                %s

                已完成步骤输出：
                %s

                请返回：
                - 执行目标
                - 执行过程
                - 执行结果
                - 质量检查
                """.formatted(
                AgentContextBoundaryService.buildPromptSection(contextBoundary),
                command.getMessage(),
                plan.getGoal(),
                JSON.toJSONString(step),
                JSON.toJSONString(stepOutputs)
        );
    }

    public String buildSupervisionPrompt(ExecuteCommandEntity command, AgentPlanVO plan, Map<String, String> stepOutputs) {
        return buildSupervisionPrompt(command, plan, stepOutputs, null);
    }

    public String buildSupervisionPrompt(ExecuteCommandEntity command,
                                         AgentPlanVO plan,
                                         Map<String, String> stepOutputs,
                                         AgentContextBoundaryVO contextBoundary) {
        return """
                你是 Flow Plan Engine 的质量监督节点。
                请检查执行结果是否满足用户目标，并只返回合法 JSON，不要输出 Markdown 或额外解释。
                JSON 结构必须为：{"passed":true,"score":8,"issues":[],"suggestions":[],"reason":"简洁原因"}
                检查时必须遵守上下文治理边界。

                %s

                用户原始请求：
                %s

                执行计划：
                %s

                步骤输出：
                %s
                """.formatted(
                AgentContextBoundaryService.buildPromptSection(contextBoundary),
                command.getMessage(),
                JSON.toJSONString(plan),
                JSON.toJSONString(stepOutputs)
        );
    }

    public String buildSummaryPrompt(ExecuteCommandEntity command,
                                     AgentPlanVO plan,
                                     Map<String, String> stepOutputs,
                                     String supervision) {
        return buildSummaryPrompt(command, plan, stepOutputs, supervision, null);
    }

    public String buildSummaryPrompt(ExecuteCommandEntity command,
                                     AgentPlanVO plan,
                                     Map<String, String> stepOutputs,
                                     String supervision,
                                     AgentContextBoundaryVO contextBoundary) {
        return """
                请基于已校验的 Flow Plan 执行结果回答用户原始请求。
                最终答案要直接、完整、可用。
                回答时必须遵守上下文治理边界。

                %s

                用户原始请求：
                %s

                执行计划：
                %s

                步骤输出：
                %s

                质量监督结果：
                %s
                """.formatted(
                AgentContextBoundaryService.buildPromptSection(contextBoundary),
                command.getMessage(),
                JSON.toJSONString(plan),
                JSON.toJSONString(stepOutputs),
                supervision
        );
    }

    public String buildLocalSummary(ExecuteCommandEntity command,
                                    AgentPlanVO plan,
                                    Map<String, String> stepOutputs,
                                    String supervision) {
        StringBuilder builder = new StringBuilder();
        builder.append("## 最终结果\n\n");
        builder.append("用户请求：").append(command.getMessage()).append("\n\n");
        builder.append("计划目标：").append(plan.getGoal()).append("\n\n");
        builder.append("已完成步骤：").append(stepOutputs.size()).append("/").append(plan.getSteps().size()).append("\n\n");
        stepOutputs.forEach((stepId, output) -> builder
                .append("### ")
                .append(stepId)
                .append("\n")
                .append(limit(output, 1000))
                .append("\n\n"));
        if (StringUtils.isNotBlank(supervision)) {
            builder.append("### 质量监督\n").append(supervision).append("\n");
        }
        return builder.toString();
    }

    public Map<String, String> compactStepOutputsAsMap(Map<String, String> stepOutputs) {
        Map<String, String> compacted = new LinkedHashMap<>();
        stepOutputs.forEach((stepId, output) -> compacted.put(stepId, limit(output, 300)));
        return compacted;
    }

    public String limit(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "...";
    }
}
