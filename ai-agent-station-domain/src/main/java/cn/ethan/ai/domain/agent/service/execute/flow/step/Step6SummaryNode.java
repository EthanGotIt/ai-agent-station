package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.ContextGuardResultVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.flow.AgentContextWindowService;
import cn.ethan.ai.domain.agent.service.execute.flow.plan.AgentPlanPromptFactory;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 步骤6：总结输出节点
 */
@Slf4j
@Service
public class Step6SummaryNode extends AbstractExecuteSupport {

    @Resource
    private AgentPlanPromptFactory promptFactory;

    @Resource
    private AgentContextWindowService agentContextWindowService;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) {
        log.info("步骤6：生成最终总结");
        if (executionContext.isCancelled()) {
            return "任务已取消";
        }
        long startTime = markStepRunning(executionContext, "flow_summary", "最终总结", 201, "SYSTEM", null);

        try {
            AgentRunAggregate run = currentRun(executionContext);
            String supervision = executionContext.getSupervisionResult();
            String summary;
            long summaryStartTime = System.currentTimeMillis();
            if (run.getContextWindowGuard().shouldStopNewLlmCall()) {
                summary = promptFactory.buildLocalSummary(requestParameter, run.getPlan(), run.stepOutputs(), supervision);
                run.getTrace().record(
                        "LOCAL_SUMMARY",
                        "summary",
                        run.getPlan().getSteps().size() + 2,
                        summaryStartTime,
                        "上下文已接近上限，跳过最终总结模型调用，使用本地总结输出。",
                        null
                );
                log.info("步骤6：上下文已接近上限，跳过最终总结模型调用，使用本地总结输出。runId：{}", run.runId());
            } else {
                ContextGuardResultVO contextGuardResult = agentContextWindowService.prepareStepOutputs(run);
                syncRunState(executionContext);
                String prompt = promptFactory.buildSummaryPrompt(requestParameter, run.getPlan(), contextGuardResult.getStepOutputs(), supervision);
                summary = agentModelPort.callModel(
                        executionContext.getAiAgentClientFlowConfigVOMap(),
                        requestParameter,
                        run.getContextWindowGuard(),
                        run.getTrace(),
                        prompt,
                        "LLM_CALL_SUMMARY",
                        "summary",
                        run.getPlan().getSteps().size() + 2,
                        null,
                        AiClientTypeEnumVO.RESPONSE_ASSISTANT,
                        AiClientTypeEnumVO.EXECUTOR_CLIENT,
                        AiClientTypeEnumVO.DEFAULT
                );
            }

            run.markSuccess(summary);
            syncRunState(executionContext);
            sendStreamResult(executionContext, AgentExecuteResultEntity.createSummaryResult(
                    summary,
                    requestParameter.getSessionId(),
                    run.runId()
            ));
            sendCompleteResult(executionContext);
            markStepSuccess(executionContext, "flow_summary", summary, startTime);
        } catch (Exception e) {
            markStepFailed(executionContext, "flow_summary", e.getMessage(), startTime);
            throw e;
        }
        return "Flow Plan 执行完成";
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, AgentExecutionContextVO, String> get(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) {
        return defaultStrategyHandler;
    }
}
