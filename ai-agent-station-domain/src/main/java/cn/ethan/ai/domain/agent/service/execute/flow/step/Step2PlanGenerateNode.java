package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanStepVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.PlanStepTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.flow.plan.AgentPlanParser;
import cn.ethan.ai.types.enums.StepIdEnum;
import cn.ethan.ai.domain.agent.service.execute.flow.plan.AgentPlanPromptFactory;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 步骤2：生成结构化执行计划节点
 */
@Slf4j
@Service
public class Step2PlanGenerateNode extends AbstractExecuteSupport {

    @Resource
    private AgentPlanParser agentPlanParser;

    @Resource
    private AgentPlanPromptFactory promptFactory;

    @Resource
    private Step3PlanValidateNode step3PlanValidateNode;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) throws Exception {
        log.info("步骤2：生成结构化执行计划");
        if (stopIfCancelled(executionContext, "任务已取消，停止生成执行计划。")) {
            return "任务已取消";
        }
        long startTime = markStepRunning(executionContext, StepIdEnum.FLOW_PLAN_GENERATE.value(), "结构化计划生成", 2, "SYSTEM");

        try {
            AgentRunAggregate run = currentRun(executionContext);
            if (!agentModelPort.hasAvailableModelClient(
                    executionContext.getAiAgentClientFlowConfigVOMap(),
                    AiClientTypeEnumVO.PLANNING_CLIENT,
                    AiClientTypeEnumVO.TASK_ANALYZER_CLIENT,
                    AiClientTypeEnumVO.DEFAULT
            )) {
                run.bindExecutionPlan(createFallbackPlan(requestParameter));
                markStepSuccess(executionContext, StepIdEnum.FLOW_PLAN_GENERATE.value(), "未找到规划模型，已使用兜底计划。", startTime);
                return router(requestParameter, executionContext);
            }

            String planningPrompt = promptFactory.buildPlanningPrompt(
                    requestParameter,
                    executionContext.getToolCapabilitySummary(),
                    executionContext.getContextBoundary()
            );
            String planText = agentModelPort.callModel(
                    executionContext.getAiAgentClientFlowConfigVOMap(),
                    requestParameter,
                    run.getContextWindowGuard(),
                    run.getTrace(),
                    planningPrompt,
                    "LLM_CALL_PLAN",
                    "plan",
                    0,
                    null,
                    AiClientTypeEnumVO.PLANNING_CLIENT,
                    AiClientTypeEnumVO.TASK_ANALYZER_CLIENT,
                    AiClientTypeEnumVO.DEFAULT
            );

            try {
                run.bindExecutionPlan(agentPlanParser.parse(planText));
            } catch (RuntimeException e) {
                log.warn("计划解析失败，尝试进行一次 JSON 修复，原因：{}", e.getMessage());
                if (run.getContextWindowGuard().shouldStopNewLlmCall()) {
                    throw e;
                }
                String repairedPlanText = agentModelPort.callModel(
                        executionContext.getAiAgentClientFlowConfigVOMap(),
                        requestParameter,
                        run.getContextWindowGuard(),
                        run.getTrace(),
                        promptFactory.buildPlanRepairPrompt(planText),
                        "LLM_CALL_PLAN_REPAIR",
                        "plan_repair",
                        0,
                        null,
                        AiClientTypeEnumVO.PLANNING_CLIENT,
                        AiClientTypeEnumVO.TASK_ANALYZER_CLIENT,
                        AiClientTypeEnumVO.DEFAULT
                );
                run.bindExecutionPlan(agentPlanParser.parse(repairedPlanText));
            }
            markStepSuccess(executionContext, StepIdEnum.FLOW_PLAN_GENERATE.value(), "结构化计划生成完成", startTime);
        } catch (Exception e) {
            markStepFailed(executionContext, StepIdEnum.FLOW_PLAN_GENERATE.value(), e.getMessage(), startTime);
            throw e;
        }

        return router(requestParameter, executionContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, AgentExecutionContextVO, String> get(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) {
        if (executionContext.isCancelled()) {
            return defaultStrategyHandler;
        }
        return step3PlanValidateNode;
    }

    private AgentPlanVO createFallbackPlan(ExecuteCommandEntity requestParameter) {
        AgentPlanStepVO step = AgentPlanStepVO.builder()
                .stepId("step_1")
                .name("执行用户请求")
                .type(PlanStepTypeEnumVO.LLM.name())
                .input(Map.of("message", requestParameter.getMessage()))
                .dependsOn(List.of())
                .successCriteria("能够回答用户的原始问题")
                .build();
        return AgentPlanVO.builder()
                .goal(requestParameter.getMessage())
                .steps(List.of(step))
                .build();
    }
}
