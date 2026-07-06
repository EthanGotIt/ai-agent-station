package cn.ethan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Checkpoint 持久化对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckpointPO {

    private Long id;

    private String checkpointId;

    private String caseId;

    private String turnId;

    private String stepId;

    private String ssmState;

    private String statePayload;

    private String stage;

    private LocalDateTime createdAt;
}
