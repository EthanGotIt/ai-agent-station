package cn.ethan.infrastructure.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.math.BigDecimal;

/**
 * 演示订单实体：映射使用大写物理名称的 DEMO_ORDER 表。
 *
 * @author ethan
 * @date 2026-08-05
 */
@TableName("DEMO_ORDER")
public final class DemoOrderEntity {

    @TableId(value = "ORDER_ID", type = IdType.INPUT)
    private String orderId;

    @TableField("USER_ID")
    private String userId;

    @TableField("STATUS")
    private String status;

    @TableField("DAYS_SINCE_DELIVERY")
    private Integer daysSinceDelivery;

    @TableField("CREATED_AT")
    private Instant createdAt;

    @TableField("EXPECTED_DELIVERY_AT")
    private Instant expectedDeliveryAt;

    @TableField("LAST_LOGISTICS_AT")
    private Instant lastLogisticsAt;

    @TableField("LOGISTICS_STATUS")
    private String logisticsStatus;

    @TableField("PAID_AMOUNT")
    private BigDecimal paidAmount;

    @TableField("CURRENCY")
    private String currency;

    public DemoOrderEntity() {
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getDaysSinceDelivery() {
        return daysSinceDelivery;
    }

    public void setDaysSinceDelivery(Integer daysSinceDelivery) {
        this.daysSinceDelivery = daysSinceDelivery;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpectedDeliveryAt() {
        return expectedDeliveryAt;
    }

    public void setExpectedDeliveryAt(Instant expectedDeliveryAt) {
        this.expectedDeliveryAt = expectedDeliveryAt;
    }

    public Instant getLastLogisticsAt() {
        return lastLogisticsAt;
    }

    public void setLastLogisticsAt(Instant lastLogisticsAt) {
        this.lastLogisticsAt = lastLogisticsAt;
    }

    public String getLogisticsStatus() {
        return logisticsStatus;
    }

    public void setLogisticsStatus(String logisticsStatus) {
        this.logisticsStatus = logisticsStatus;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
