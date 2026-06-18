package cn.ethan.ai.domain.agent.model.valobj;

import cn.ethan.ai.domain.agent.model.valobj.enums.AgentActionTypeEnumVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Harness 动作执行后的观测。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HarnessObservationVO {

    private String actionId;

    private AgentActionTypeEnumVO actionType;

    private boolean success;

    private boolean terminal;

    private String message;

    @Builder.Default
    private Map<String, Object> payload = new LinkedHashMap<>();

    public static HarnessObservationVO success(AgentActionVO action, String message, Map<String, Object> payload, boolean terminal) {
        return HarnessObservationVO.builder()
                .actionId(action == null ? null : action.getActionId())
                .actionType(action == null ? null : action.getType())
                .success(true)
                .terminal(terminal)
                .message(message)
                .payload(payload == null ? new LinkedHashMap<>() : payload)
                .build();
    }

    public static HarnessObservationVO failure(AgentActionVO action, String message, boolean terminal) {
        return HarnessObservationVO.builder()
                .actionId(action == null ? null : action.getActionId())
                .actionType(action == null ? null : action.getType())
                .success(false)
                .terminal(terminal)
                .message(message)
                .payload(new LinkedHashMap<>())
                .build();
    }
}
