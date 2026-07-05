package cn.ethan.ai.domain.agent.service;

import cn.ethan.ai.domain.agent.exception.AfterSalesResumeConflictException;
import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesResumeCommand;
import cn.ethan.ai.domain.agent.model.AfterSalesRunCommand;
import cn.ethan.ai.domain.agent.model.AfterSalesRunResult;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesStateMachine;

import java.util.Optional;

import static cn.ethan.ai.types.common.util.Strings.isBlank;

/**
 * 售后Agent服务门面，向后兼容地委托给生命周期、授权与审计服务。
 */
public final class AfterSalesAgentService {

    private final AfterSalesCaseLifecycleService lifecycleService;
    private final AfterSalesAuthorizationService authorizationService;
    private final IAfterSalesRepository repository;

    public AfterSalesAgentService(IAfterSalesStateMachine stateMachine,
                                  IAfterSalesRepository repository,
                                  AfterSalesAuditService auditService) {
        this.lifecycleService = new AfterSalesCaseLifecycleService(stateMachine, repository, auditService);
        this.authorizationService = new AfterSalesAuthorizationService();
        this.repository = repository;
    }

    public AfterSalesRunResult start(AfterSalesRunCommand command) {
        return lifecycleService.start(command);
    }

    public AfterSalesRunResult resume(AfterSalesResumeCommand command) {
        AfterSalesCaseView caseView = repository.findCase(command.caseIdValue())
                .orElseThrow(() -> new IllegalArgumentException("售后Case不存在，caseId=" + command.caseIdValue()));
        authorizationService.assertResumable(caseView);
        authorizationService.authorizeResume(caseView, command);
        return lifecycleService.resume(command, caseView);
    }

    public Optional<AfterSalesCaseView> query(String caseId) {
        return isBlank(caseId) ? Optional.empty() : repository.findCase(caseId);
    }

    public Optional<AfterSalesCaseView> query(String caseId, String requesterId, String requesterRole) {
        Optional<AfterSalesCaseView> result = query(caseId);
        if (result.isPresent() && !authorizationService.canAccess(result.get(), requesterId, requesterRole)) {
            throw new SecurityException("无权访问该售后Case");
        }
        return result;
    }

    public boolean cancel(String caseId, String reason) {
        return lifecycleService.cancel(caseId, reason);
    }

    public boolean cancel(String caseId, String requesterId, String reason) {
        AfterSalesCaseView current = repository.findCase(caseId)
                .orElseThrow(() -> new IllegalArgumentException("售后Case不存在，caseId=" + caseId));
        if (!current.userIdValue().equals(requesterId)) {
            throw new SecurityException("只有Case所有者可以取消");
        }
        return cancel(caseId, reason);
    }
}
