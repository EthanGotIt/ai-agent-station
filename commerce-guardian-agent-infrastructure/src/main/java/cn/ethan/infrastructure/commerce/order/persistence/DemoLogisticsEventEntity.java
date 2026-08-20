package cn.ethan.infrastructure.commerce.order.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 演示物流事件实体：保存可排序的订单履约轨迹。
 *
 * @author ethan
 * @date 2026-08-10
 */
@TableName("DEMO_LOGISTICS_EVENT")
public final class DemoLogisticsEventEntity {

    @TableId(value = "EVENT_ID", type = IdType.INPUT)
    private String eventId;
    @TableField("ORDER_ID")
    private String orderId;
    @TableField("STATUS")
    private String status;
    @TableField("LOCATION")
    private String location;
    @TableField("DESCRIPTION")
    private String description;
    @TableField("OCCURRED_AT")
    private Instant occurredAt;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
