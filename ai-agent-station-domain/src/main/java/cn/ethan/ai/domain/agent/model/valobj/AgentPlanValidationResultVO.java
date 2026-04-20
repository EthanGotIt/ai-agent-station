package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AgentPlanValidationResultVO {

    private final boolean valid;

    private final List<String> errors;

    public static AgentPlanValidationResultVO ok() {
        return new AgentPlanValidationResultVO(true, Collections.emptyList());
    }

    public static AgentPlanValidationResultVO invalid(List<String> errors) {
        return new AgentPlanValidationResultVO(false, Collections.unmodifiableList(new ArrayList<>(errors)));
    }

    public String formatErrors() {
        return String.join("; ", errors);
    }
}
