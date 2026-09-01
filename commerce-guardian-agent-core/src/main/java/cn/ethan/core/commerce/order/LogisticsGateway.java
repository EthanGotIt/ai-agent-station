package cn.ethan.core.commerce.order;


import java.util.List;

/**
 * 物流网关：为追踪和履约诊断提供按订单归属隔离的事件时间线。
 *
 * @author ethan
 * @date 2026-08-10
 */
public interface LogisticsGateway {

    List<LogisticsEventModel> findTrace(String orderId, String userId);
}
