package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.ContextGuardResultVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.flow.AgentContextBoundaryService;
import cn.ethan.ai.domain.agent.service.execute.flow.AgentContextWindowService;
import cn.ethan.ai.domain.agent.service.execute.flow.plan.AgentPlanPromptFactory;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.types.enums.StepIdEnum;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 步骤5：执行质量监督节点
 */
@Slf4j
@Service
public class Step5QualitySupervisorNode extends AbstractExecuteSupport {

    @Resource
    private AgentPlanPromptFactory promptFactory;

    @Resource
    private AgentContextWindowService agentContextWindowService;

    @Resource
    private Step6SummaryNode step6SummaryNode;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) throws Exception {
        log.info("步骤5：监督执行质量");
        if (stopIfCancelled(executionContext, "任务已取消，跳过质量监督。")) {
            return "任务已取消";
        }
        long startTime = markStepRunning(executionContext, StepIdEnum.FLOW_SUPERVISION.value(), "质量监督", 200, "SYSTEM");

        try {
            AgentRunAggregate run = currentRun(executionContext);
            String supervision;
            if (run.getContextWindowGuard().shouldStopNewLlmCall()) {
                supervision = "通过：已完成 " + run.stepOutputs().size() + "/" + run.getPlan().getSteps().size()
                        + " 个计划步骤。";
            } else {
                ContextGuardResultVO contextGuardResult = agentContextWindowService.prepareStepOutputs(run);
                AgentContextBoundaryService.attachRunSummary(
                        executionContext.getContextBoundary(),
                        contextGuardResult.getHistorySummary()
                );
                syncRunState(executionContext);
                String prompt = promptFactory.buildSupervisionPrompt(
                        requestParameter,
                        run.getPlan(),
                        contextGuardResult.getStepOutputs(),
                        executionContext.getContextBoundary()
                );
                supervision = agentModelPort.callModel(
                        executionContext.getAiAgentClientFlowConfigVOMap(),
                        requestParameter,
                        run.getContextWindowGuard(),
                        run.getTrace(),
                        prompt,
                        "LLM_CALL_SUPERVISION",
                        "supervision",
                        run.getPlan().getSteps().size() + 1,
                        null,
                        AiClientTypeEnumVO.QUALITY_SUPERVISOR_CLIENT,
                        AiClientTypeEnumVO.DEFAULT
                );
            }

            executionContext.setSupervisionResult(supervision);
            sendStreamResult(executionContext, AgentExecuteResultEntity.createSupervisionResult(
                    run.getPlan().getSteps().size() + 1,
                    supervision,
                    requestParameter.getSessionId(),
                    run.runId()
            ));
            markStepSuccess(executionContext, StepIdEnum.FLOW_SUPERVISION.value(), supervision, startTime);
        } catch (Exception e) {
            markStepFailed(executionContext, StepIdEnum.FLOW_SUPERVISION.value(), e.getMessage(), startTime);
            throw e;
        }

        return router(requestParameter, executionContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, AgentExecutionContextVO, String> get(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) {
        if (executionContext.isCancelled()) {
            return defaultStrategyHandler;
        }
        return step6SummaryNode;
    }
}
