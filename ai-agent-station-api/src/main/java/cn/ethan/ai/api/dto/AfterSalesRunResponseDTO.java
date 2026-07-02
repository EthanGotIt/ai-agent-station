package cn.ethan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AfterSalesRunResponseDTO {
    private String runId;
    private String caseId;
    private String stage;
    private String checkpointId;
    private String nextNode;
    private String waitingReason;
    private String terminalReason;
    private String commandId;
    private Map<String, Object> state;
}
