package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanValidationResultVO;
import cn.ethan.ai.domain.agent.service.execute.flow.plan.AgentPlanValidator;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 步骤3：执行计划校验节点
 */
@Slf4j
@Service
public class Step3PlanValidateNode extends AbstractExecuteSupport {

    @Resource
    private AgentPlanValidator agentPlanValidator;

    @Resource
    private Step4PlanExecuteNode step4PlanExecuteNode;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) throws Exception {
        log.info("步骤3：校验结构化执行计划");

        AgentRunAggregate run = currentRun(executionContext);
        AgentPlanValidationResultVO validationResult = agentPlanValidator.validate(
                run.getPlan(),
                executionContext.getMaxStep(),
                executionContext.getAllowedTools()
        );
        executionContext.setPlanValid(validationResult.isValid());

        if (!validationResult.isValid()) {
            sendErrorResult(executionContext, "执行计划校验失败：" + validationResult.formatErrors());
            return "执行计划校验失败";
        }

        sendStreamResult(executionContext, AgentExecuteResultEntity.createAnalysisSubResult(
                nextStreamStep(executionContext),
                "analysis_plan",
                JSON.toJSONString(run.getPlan()),
                requestParameter.getSessionId(),
                run.runId()
        ));

        return router(requestParameter, executionContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, AgentExecutionContextVO, String> get(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) {
        return executionContext.isPlanValid() ? step4PlanExecuteNode : defaultStrategyHandler;
    }
}
