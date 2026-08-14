import type { ScenarioDefinition } from "./types";

/** 控制台快速场景：只复用初始化脚本已提供的真实演示订单与能力边界。 */
export const SCENARIOS: readonly ScenarioDefinition[] = [
  {
    id: "order",
    title: "查询订单",
    description: "查看已支付订单的商品、金额和状态。",
    message: "查询订单 ORDER-PAID-001"
  },
  {
    id: "logistics",
    title: "诊断物流停滞",
    description: "分析已发货订单为何长时间没有更新。",
    message: "订单 ORDER-SHIPPED-STALLED-001 物流停滞怎么办"
  },
  {
    id: "refund",
    title: "发起退款 Workflow",
    description: "进入可恢复的退款信息收集和确认流程。",
    message: "订单 ORDER-PAID-001 退款"
  },
  {
    id: "preference",
    title: "保存回答偏好",
    description: "通过 ASK 确认，保存当前会话的低风险偏好。",
    message: "以后请默认使用英文回答，并保持简洁；请保存这个会话偏好。"
  }
];
