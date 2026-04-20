package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanStepVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.PlanStepTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.flow.plan.AgentPlanPromptFactory;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 步骤4：按执行计划顺序执行节点
 */
@Slf4j
@Service
public class Step4PlanExecuteNode extends AbstractExecuteSupport {

    @Resource
    private AgentPlanPromptFactory promptFactory;

    @Resource
    private Step5QualitySupervisorNode step5QualitySupervisorNode;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) throws Exception {
        log.info("步骤4：按计划执行任务步骤");

        AgentRunAggregate run = currentRun(executionContext);
        int stepIndex = 1;
        for (AgentPlanStepVO step : run.getPlan().getSteps()) {
            if (run.getContextWindowGuard().shouldStopNewLlmCall()) {
                sendStreamResult(executionContext, AgentExecuteResultEntity.createExecutionSubResult(
                        stepIndex,
                        "execution_quality",
                        "上下文较长，停止新的模型调用，并基于已有结果生成总结。",
                        requestParameter.getSessionId(),
                        run.runId()
                ));
                break;
            }

            sendStreamResult(executionContext, AgentExecuteResultEntity.createExecutionSubResult(
                    stepIndex,
                    "execution_target",
                    step.getName(),
                    requestParameter.getSessionId(),
                    run.runId()
            ));

            String output = executeSingleStep(requestParameter, executionContext, run, step, stepIndex);
            run.recordStepOutput(step.getStepId(), output);
            sendStreamResult(executionContext, AgentExecuteResultEntity.createExecutionResult(
                    stepIndex,
                    output,
                    requestParameter.getSessionId(),
                    run.runId()
            ));

            if (run.getContextWindowGuard().shouldCompactHistory()) {
                run.getContextWindowGuard().markHistoryCompacted();
                sendStreamResult(executionContext, AgentExecuteResultEntity.createAnalysisSubResult(
                        stepIndex,
                        "analysis_progress",
                        "上下文较长，后续步骤将使用压缩后的历史摘要。",
                        requestParameter.getSessionId(),
                        run.runId()
                ));
            }
            stepIndex++;
        }

        return router(requestParameter, executionContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, AgentExecutionContextVO, String> get(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) {
        return step5QualitySupervisorNode;
    }

    private String executeSingleStep(ExecuteCommandEntity requestParameter,
                                     AgentExecutionContextVO executionContext,
                                     AgentRunAggregate run,
                                     AgentPlanStepVO step,
                                     Integer stepIndex) {
        if (PlanStepTypeEnumVO.SUMMARY.name().equalsIgnoreCase(step.getType())) {
            return promptFactory.buildLocalSummary(requestParameter, run.getPlan(), run.stepOutputs(), "");
        }

        Map<String, String> promptStepOutputs = run.getContextWindowGuard().isHistoryCompacted()
                ? promptFactory.compactStepOutputsAsMap(run.stepOutputs())
                : run.stepOutputs();
        String prompt = promptFactory.buildStepExecutionPrompt(requestParameter, run.getPlan(), step, promptStepOutputs);
        return agentModelPort.callModel(
                executionContext.getAiAgentClientFlowConfigVOMap(),
                requestParameter,
                run.getContextWindowGuard(),
                run.getTrace(),
                prompt,
                "LLM_CALL_STEP",
                step.getStepId(),
                stepIndex,
                AiClientTypeEnumVO.EXECUTOR_CLIENT,
                AiClientTypeEnumVO.PRECISION_EXECUTOR_CLIENT,
                AiClientTypeEnumVO.DEFAULT
        );
    }
}
