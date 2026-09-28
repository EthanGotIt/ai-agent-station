package cn.ethan.core.agent.workflow;

/**
 * 类型职责：在本地事务内独占用户订单的未完成写事项，避免跨 Thread 重复创建命令。
 *
 * @author ethan
 * @date 2026-09-28
 */
public interface OrderWriteReservationStore {

    /** 同一 Run 重复预留视为幂等；其他 Run 已占用时返回 false。 */
    boolean reserve(String userId, String orderId, String runId);

    /** 仅持有者可释放；成功回执但事实未核验时应继续保留。 */
    void release(String userId, String orderId, String runId);
}
