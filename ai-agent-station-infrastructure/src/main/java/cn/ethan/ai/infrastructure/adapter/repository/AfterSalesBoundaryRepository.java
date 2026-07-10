package cn.ethan.ai.infrastructure.adapter.repository;

import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.Checkpoint;
import cn.ethan.ai.domain.agent.model.valobj.AgentTurnRecord;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesBoundaryRepository;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.port.driven.IAgentTurnRepository;
import cn.ethan.ai.domain.agent.port.driven.ICheckpointRepository;
import cn.ethan.ai.infrastructure.observability.AfterSalesRuntimeMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 使用同一 MySQL 事务提交 checkpoint、Case 指针和 Turn 结果。
 */
@Repository
public class AfterSalesBoundaryRepository implements IAfterSalesBoundaryRepository {

    private final IAfterSalesRepository afterSalesRepository;
    private final ICheckpointRepository checkpointRepository;
    private final IAgentTurnRepository turnRepository;
    private final TransactionTemplate transactionTemplate;
    private final AfterSalesRuntimeMetrics metrics;

    @Autowired
    public AfterSalesBoundaryRepository(IAfterSalesRepository afterSalesRepository,
                                        ICheckpointRepository checkpointRepository,
                                        IAgentTurnRepository turnRepository,
                                        @Qualifier("mysqlTransactionTemplate") TransactionTemplate transactionTemplate,
                                        AfterSalesRuntimeMetrics metrics) {
        this.afterSalesRepository = afterSalesRepository;
        this.checkpointRepository = checkpointRepository;
        this.turnRepository = turnRepository;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
    }

    public AfterSalesBoundaryRepository(IAfterSalesRepository afterSalesRepository,
                                        ICheckpointRepository checkpointRepository,
                                        IAgentTurnRepository turnRepository,
                                        TransactionTemplate transactionTemplate) {
        this(afterSalesRepository, checkpointRepository, turnRepository,
                transactionTemplate, AfterSalesRuntimeMetrics.noop());
    }

    @Override
    public void commit(AfterSalesCaseView caseView, Checkpoint checkpoint, AgentTurnRecord completedTurn) {
        transactionTemplate.executeWithoutResult(status -> {
            checkpointRepository.save(checkpoint);
            afterSalesRepository.updateCase(caseView);
            turnRepository.completeTurn(completedTurn);
        });
        metrics.recordCheckpoint("boundary", checkpoint.stage().name());
        metrics.recordBoundary(checkpoint.stage().name());
    }
}
