package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AfterSalesCasePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AfterSalesCaseMapper {

    int insert(AfterSalesCasePO afterSalesCase);

    int updateByRunId(AfterSalesCasePO afterSalesCase);

    AfterSalesCasePO selectByRunId(@Param("runId") String runId);

    int cancelByRunId(@Param("runId") String runId, @Param("reason") String reason);

    int markRefundExecuting(@Param("caseId") String caseId, @Param("commandId") String commandId);
}
