package cn.ethan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AfterSalesRunRequestDTO {
    private String userId;
    private String sessionId;
    private String message;
    private String orderId;
    private String refundReason;
}
