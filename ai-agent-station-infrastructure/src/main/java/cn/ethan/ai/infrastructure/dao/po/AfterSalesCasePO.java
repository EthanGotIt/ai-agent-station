package cn.ethan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AfterSalesCasePO {

    private String caseId;
    private String userId;
    private String sessionId;
    private String userMessage;
    private String orderId;
    private String stage;
    private String checkpointId;
    private String nextNode;
    private String terminalReason;
    private String commandId;
}
