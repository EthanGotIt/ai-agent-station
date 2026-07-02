package cn.ethan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DemoOrderPO {

    private String orderId;
    private String userId;
    private String status;
    private Integer daysSinceDelivery;
}
