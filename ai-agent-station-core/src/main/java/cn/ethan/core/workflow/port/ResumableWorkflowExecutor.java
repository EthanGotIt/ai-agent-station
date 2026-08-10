package cn.ethan.core.workflow.port;

import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.workflow.model.WorkflowAnswerRequestModel;
import cn.ethan.core.workflow.model.WorkflowResultModel;

import java.util.Map;

/**
 * 可恢复 Workflow 执行器：从持久化检查点继续同一业务流程。
 *
 * @author ethan
 * @date 2026-08-07
 */
public interface ResumableWorkflowExecutor extends WorkflowExecutor {

    WorkflowResultModel answer(
            WorkflowAnswerRequestModel request,
            String userId,
            CancellationToken cancellationToken
    );

    default WorkflowResultModel answer(
            WorkflowAnswerRequestModel request,
            String userId,
            CancellationToken cancellationToken,
            Map<String, Object> memorySuggestions
    ) {
        return answer(request, userId, cancellationToken);
    }
}
