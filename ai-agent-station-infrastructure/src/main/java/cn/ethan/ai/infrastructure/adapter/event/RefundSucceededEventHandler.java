package cn.ethan.ai.infrastructure.adapter.event;

import cn.ethan.ai.domain.agent.port.driving.IAfterSalesEventHandler;
import cn.ethan.ai.domain.agent.model.AfterSalesDomainEvent;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;

@Component
public class RefundSucceededEventHandler implements IAfterSalesEventHandler {

    @Override
    public boolean supports(String eventType) {
        return "REFUND_SUCCEEDED".equals(eventType);
    }

    @Override
    public void handle(AfterSalesDomainEvent event) {
        JSONObject payload = JSON.parseObject(event.payload());
        if (payload == null || payload.getString("caseId") == null || payload.getString("commandId") == null) {
            throw new IllegalArgumentException("REFUND_SUCCEEDED payload is incomplete");
        }
    }
}
