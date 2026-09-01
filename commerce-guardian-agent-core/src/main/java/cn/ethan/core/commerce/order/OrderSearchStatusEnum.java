package cn.ethan.core.commerce.order;

/**
 * 订单搜索的基础设施结果：区分空结果和订单服务临时不可用。
 *
 * @author ethan
 * @date 2026-08-22
 */
public enum OrderSearchStatusEnum {
    SUCCESS,
    TEMPORARY_FAILURE
}
