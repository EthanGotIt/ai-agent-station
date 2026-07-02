package cn.ethan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AfterSalesResumeRequestDTO {
    private String checkpointId;
    private String action;
    private String orderId;
    private String refundReason;
}
