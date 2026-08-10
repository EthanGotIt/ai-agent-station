package cn.ethan.core.workflow.model;

import java.util.List;

/**
 * Workflow 描述模型：声明领域与固定流程的一对一归属及受支持操作。
 *
 * @author ethan
 * @date 2026-08-07
 */
public record WorkflowDescriptorModel(
        String domainId,
        String workflowId,
        String version,
        List<String> operations
) {

    public WorkflowDescriptorModel {
        if (domainId == null || domainId.isBlank()
                || workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("domainId and workflowId are required");
        }
        version = version == null || version.isBlank() ? "v1" : version;
        operations = operations == null ? List.of() : List.copyOf(operations);
    }

    public boolean supportsOperation(String operation) {
        return operation != null && operations.contains(operation);
    }
}
