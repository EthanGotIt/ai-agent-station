package cn.ethan.core.workflow.service;

import cn.ethan.core.workflow.model.WorkflowDescriptorModel;
import cn.ethan.core.workflow.port.WorkflowExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Workflow 注册服务：按稳定流程标识与领域标识分发唯一执行器。
 *
 * @author ethan
 * @date 2026-08-06
 */
public final class WorkflowRegistryService {

    private final Map<String, WorkflowExecutor> workflows;
    private final Map<String, WorkflowExecutor> domainWorkflows;

    public WorkflowRegistryService(List<? extends WorkflowExecutor> executors) {
        Map<String, WorkflowExecutor> registered = new LinkedHashMap<>();
        for (WorkflowExecutor executor : executors == null ? List.<WorkflowExecutor>of() : executors) {
            if (executor == null
                    || executor.workflowId() == null
                    || executor.workflowId().isBlank()) {
                throw new IllegalArgumentException("workflow executor and id are required");
            }
            WorkflowExecutor previous = registered.putIfAbsent(executor.workflowId(), executor);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate workflow id: " + executor.workflowId()
                );
            }
        }
        this.workflows = Map.copyOf(registered);

        Map<String, WorkflowExecutor> registeredDomains = new LinkedHashMap<>();
        for (WorkflowExecutor executor : registered.values()) {
            WorkflowDescriptorModel descriptor = executor.descriptor();
            if (!executor.workflowId().equals(descriptor.workflowId())) {
                throw new IllegalArgumentException("workflow descriptor id mismatch: " + executor.workflowId());
            }
            WorkflowExecutor previous = registeredDomains.putIfAbsent(descriptor.domainId(), executor);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate workflow domain: " + descriptor.domainId());
            }
        }
        this.domainWorkflows = Map.copyOf(registeredDomains);
    }

    public Optional<WorkflowExecutor> find(String workflowId) {
        return Optional.ofNullable(workflows.get(workflowId));
    }

    public boolean contains(String workflowId) {
        return workflows.containsKey(workflowId);
    }

    /**
     * 按领域查找唯一工作流，确保一个业务领域只对应一个 Workflow。
     *
     * @param domainId 领域标识
     * @return 匹配的执行器
     */
    public Optional<WorkflowExecutor> findByDomain(String domainId) {
        return Optional.ofNullable(domainWorkflows.get(domainId));
    }

    public boolean containsDomain(String domainId) {
        return domainWorkflows.containsKey(domainId);
    }

}
