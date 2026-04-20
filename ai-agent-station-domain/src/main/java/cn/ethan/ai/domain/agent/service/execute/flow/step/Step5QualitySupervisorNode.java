package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.flow.plan.AgentPlanPromptFactory;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
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
    private Step6SummaryNode step6SummaryNode;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) throws Exception {
        log.info("步骤5：监督执行质量");

        AgentRunAggregate run = currentRun(executionContext);
        String supervision;
        if (run.getContextWindowGuard().shouldStopNewLlmCall()) {
            supervision = "通过：已完成 " + run.stepOutputs().size() + "/" + run.getPlan().getSteps().size()
                    + " 个计划步骤。";
        } else {
            String prompt = promptFactory.buildSupervisionPrompt(requestParameter, run.getPlan(), run.stepOutputs());
            supervision = agentModelPort.callModel(
                    executionContext.getAiAgentClientFlowConfigVOMap(),
                    requestParameter,
                    run.getContextWindowGuard(),
                    run.getTrace(),
                    prompt,
                    "LLM_CALL_SUPERVISION",
                    "supervision",
                    run.getPlan().getSteps().size() + 1,
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

        return router(requestParameter, executionContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, AgentExecutionContextVO, String> get(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) {
        return step6SummaryNode;
    }
}
