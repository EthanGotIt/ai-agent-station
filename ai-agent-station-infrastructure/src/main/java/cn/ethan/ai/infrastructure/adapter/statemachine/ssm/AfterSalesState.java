package cn.ethan.ai.infrastructure.adapter.statemachine.ssm;

/**
 * Spring State Machine 售后退款状态。
 */
public enum AfterSalesState {
    INTAKE,
    PENDING_APPROVAL,
    COMPLETED,
    REJECTED
}
