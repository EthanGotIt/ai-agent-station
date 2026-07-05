package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AfterSalesOutboxPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AfterSalesOutboxMapper {

    int insertIgnore(AfterSalesOutboxPO event);

    List<AfterSalesOutboxPO> selectDispatchable(@Param("now") LocalDateTime now,
                                                @Param("limit") int limit);

    int claim(@Param("eventId") String eventId,
              @Param("workerId") String workerId,
              @Param("lockedUntil") LocalDateTime lockedUntil,
              @Param("now") LocalDateTime now);

    int markDelivered(@Param("eventId") String eventId,
                      @Param("workerId") String workerId,
                      @Param("deliveredAt") LocalDateTime deliveredAt);

    int markFailed(@Param("eventId") String eventId,
                   @Param("workerId") String workerId,
                   @Param("status") String status,
                   @Param("retryCount") int retryCount,
                   @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                   @Param("lastError") String lastError);

    int insertConsumerReceipt(@Param("eventId") String eventId,
                              @Param("consumerName") String consumerName);

    int markConsumerSuccess(@Param("eventId") String eventId,
                            @Param("consumerName") String consumerName);
}
