package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.DemoOrderPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DemoOrderMapper {

    DemoOrderPO selectByOrderId(@Param("orderId") String orderId);

    int updateStatusForRefund(@Param("orderId") String orderId, @Param("userId") String userId);
}
