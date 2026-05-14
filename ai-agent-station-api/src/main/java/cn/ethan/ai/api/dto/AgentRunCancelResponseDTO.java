package cn.ethan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunCancelResponseDTO {

    private String runId;

    private boolean cancelled;

    private String message;

}
