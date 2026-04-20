package cn.ethan.ai.domain.agent.service.execute.flow.plan;

import cn.ethan.ai.domain.agent.model.valobj.AgentPlanStepVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanValidationResultVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.PlanStepTypeEnumVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 执行计划校验器
 */
@Service
public class AgentPlanValidator {

    public AgentPlanValidationResultVO validate(AgentPlanVO plan, Integer maxStep, Set<String> allowedTools) {
        List<String> errors = new ArrayList<>();
        if (plan == null) {
            errors.add("执行计划为空");
            return AgentPlanValidationResultVO.invalid(errors);
        }
        if (plan.getSteps() == null || plan.getSteps().isEmpty()) {
            errors.add("执行计划步骤为空");
            return AgentPlanValidationResultVO.invalid(errors);
        }

        int effectiveMaxStep = maxStep == null || maxStep <= 0 ? 5 : maxStep;
        if (plan.getSteps().size() > effectiveMaxStep) {
            errors.add("执行计划步骤数超过 maxStep " + effectiveMaxStep);
        }

        Set<String> normalizedTools = allowedTools == null ? Set.of() : allowedTools.stream()
                .filter(StringUtils::isNotBlank)
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        Set<String> stepIds = new HashSet<>();
        for (AgentPlanStepVO step : plan.getSteps()) {
            if (step == null) {
                errors.add("执行计划包含空步骤");
                continue;
            }
            if (StringUtils.isBlank(step.getStepId())) {
                errors.add("stepId 不能为空");
            } else if (!stepIds.add(step.getStepId())) {
                errors.add("重复的 stepId " + step.getStepId());
            }
            if (StringUtils.isBlank(step.getName())) {
                errors.add("步骤 " + step.getStepId() + " 的 name 不能为空");
            }
            if (!PlanStepTypeEnumVO.contains(step.getType())) {
                errors.add("步骤 " + step.getStepId() + " 的 type 不支持：" + step.getType());
            }
            if (PlanStepTypeEnumVO.requiresTool(step.getType())) {
                if (StringUtils.isBlank(step.getToolName())) {
                    errors.add("工具步骤 " + step.getStepId() + " 的 toolName 不能为空");
                } else if (!normalizedTools.isEmpty()
                        && !normalizedTools.contains(step.getToolName().trim().toLowerCase(Locale.ROOT))) {
                    errors.add("工具 " + step.getToolName() + " 不在白名单内");
                }
            }
        }

        Set<String> knownStepIds = new HashSet<>();
        for (AgentPlanStepVO step : plan.getSteps()) {
            if (step == null || StringUtils.isBlank(step.getStepId())) {
                continue;
            }
            if (step.getDependsOn() != null) {
                for (String dependency : step.getDependsOn()) {
                    if (StringUtils.isBlank(dependency)) {
                        errors.add("步骤 " + step.getStepId() + " 存在空依赖");
                    } else if (!knownStepIds.contains(dependency)) {
                        errors.add("步骤 " + step.getStepId() + " 依赖不存在或更晚的步骤 " + dependency);
                    }
                }
            }
            knownStepIds.add(step.getStepId());
        }

        return errors.isEmpty() ? AgentPlanValidationResultVO.ok() : AgentPlanValidationResultVO.invalid(errors);
    }
}
