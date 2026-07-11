package cn.ethan.ai.infrastructure.adapter.event;

import cn.ethan.ai.domain.agent.port.driving.IAfterSalesEventHandler;
import cn.ethan.ai.domain.agent.model.AfterSalesDomainEvent;
import cn.ethan.ai.infrastructure.json.AfterSalesJsonCodec;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.util.Map;

@Component
public class RefundSucceededEventHandler implements IAfterSalesEventHandler {

    private final AfterSalesJsonCodec jsonCodec;

    public RefundSucceededEventHandler(AfterSalesJsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    @Override
    public boolean supports(String eventType) {
        return "REFUND_SUCCEEDED".equals(eventType);
    }

    @Override
    public void handle(AfterSalesDomainEvent event) {
        Map<String, Object> payload = jsonCodec.read(event.payload(), new TypeReference<>() {
        }, "解析退款成功事件");
        if (payload == null || payload.get("caseId") == null || payload.get("commandId") == null) {
            throw new IllegalArgumentException("REFUND_SUCCEEDED payload is incomplete");
        }
    }
}
