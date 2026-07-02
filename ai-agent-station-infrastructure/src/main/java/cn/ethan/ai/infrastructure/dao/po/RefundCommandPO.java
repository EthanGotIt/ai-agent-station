package cn.ethan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundCommandPO {

    private String commandId;
    private String caseId;
    private String orderId;
    private String userId;
    private String idempotencyKey;
    private String status;
    private String failureReason;
}
