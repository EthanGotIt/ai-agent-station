package cn.ethan.ai.test.fixture;

import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.Checkpoint;
import cn.ethan.ai.domain.agent.model.valobj.AgentTurnRecord;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesBoundaryRepository;

/**
 * 单元测试使用的 Turn 边界提交器。
 */
public final class InMemoryAfterSalesBoundaryRepository implements IAfterSalesBoundaryRepository {

    private final InMemoryAfterSalesRepository afterSalesRepository;
    private final InMemoryCheckpointRepository checkpointRepository;

    public InMemoryAfterSalesBoundaryRepository(InMemoryAfterSalesRepository afterSalesRepository,
                                                InMemoryCheckpointRepository checkpointRepository) {
        this.afterSalesRepository = afterSalesRepository;
        this.checkpointRepository = checkpointRepository;
    }

    @Override
    public void commit(AfterSalesCaseView caseView, Checkpoint checkpoint, AgentTurnRecord completedTurn) {
        checkpointRepository.save(checkpoint);
        afterSalesRepository.updateCase(caseView);
    }
}
