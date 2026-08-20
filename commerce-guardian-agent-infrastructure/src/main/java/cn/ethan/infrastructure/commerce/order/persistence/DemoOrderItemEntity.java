package cn.ethan.infrastructure.commerce.order.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;

/**
 * 演示订单商品实体：映射订单详情卡需要的商品行。
 *
 * @author ethan
 * @date 2026-08-10
 */
@TableName("DEMO_ORDER_ITEM")
public final class DemoOrderItemEntity {

    @TableId(value = "ITEM_ID", type = IdType.INPUT)
    private String itemId;
    @TableField("ORDER_ID")
    private String orderId;
    @TableField("PRODUCT_NAME")
    private String productName;
    @TableField("QUANTITY")
    private Integer quantity;
    @TableField("UNIT_PRICE")
    private BigDecimal unitPrice;

    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
}
