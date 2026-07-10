package cn.ethan.ai.domain.agent.port.driven;

import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.Checkpoint;
import cn.ethan.ai.domain.agent.model.valobj.AgentTurnRecord;

/**
 * 原子提交一次已完成 Turn 的可恢复边界。
 */
public interface IAfterSalesBoundaryRepository {

    void commit(AfterSalesCaseView caseView, Checkpoint checkpoint, AgentTurnRecord completedTurn);
}
