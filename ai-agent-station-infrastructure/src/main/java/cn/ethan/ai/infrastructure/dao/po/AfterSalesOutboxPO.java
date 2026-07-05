package cn.ethan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AfterSalesOutboxPO {

    private String eventId;
    private String aggregateId;
    private String eventType;
    private String payload;
    private String status;
    private Integer retryCount;
    private java.time.LocalDateTime nextAttemptAt;
    private String lockedBy;
    private java.time.LocalDateTime lockedUntil;
    private String lastError;
    private java.time.LocalDateTime deliveredAt;
}
