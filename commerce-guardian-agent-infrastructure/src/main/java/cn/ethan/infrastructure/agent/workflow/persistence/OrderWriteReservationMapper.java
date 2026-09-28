package cn.ethan.infrastructure.agent.workflow.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 类型职责：以数据库唯一约束串行化同一用户订单的 Workflow 写事项。
 *
 * @author ethan
 * @date 2026-09-28
 */
@Mapper
public interface OrderWriteReservationMapper {

    @Insert("INSERT IGNORE INTO AGENT_ORDER_WRITE_RESERVATION (USER_ID, ORDER_ID, RUN_ID) "
            + "VALUES (#{userId}, #{orderId}, #{runId})")
    int reserve(String userId, String orderId, String runId);

    @Select("SELECT RUN_ID FROM AGENT_ORDER_WRITE_RESERVATION "
            + "WHERE USER_ID = #{userId} AND ORDER_ID = #{orderId}")
    String holder(String userId, String orderId);

    @Delete("DELETE FROM AGENT_ORDER_WRITE_RESERVATION "
            + "WHERE USER_ID = #{userId} AND ORDER_ID = #{orderId} AND RUN_ID = #{runId}")
    int release(String userId, String orderId, String runId);
}
