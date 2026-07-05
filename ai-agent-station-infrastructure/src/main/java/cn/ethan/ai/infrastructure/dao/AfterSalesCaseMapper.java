package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AfterSalesCasePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AfterSalesCaseMapper {

    int insert(AfterSalesCasePO afterSalesCase);

    int updateByCaseId(AfterSalesCasePO afterSalesCase);

    AfterSalesCasePO selectByCaseId(@Param("caseId") String caseId);

    int cancelByCaseId(@Param("caseId") String caseId, @Param("reason") String reason);

    int markRefundExecuting(@Param("caseId") String caseId, @Param("commandId") String commandId);

    int tryAcquireResume(@Param("caseId") String caseId,
                         @Param("checkpointId") String checkpointId,
                         @Param("resumeToken") String resumeToken);

    int releaseResume(@Param("caseId") String caseId, @Param("resumeToken") String resumeToken);
}
