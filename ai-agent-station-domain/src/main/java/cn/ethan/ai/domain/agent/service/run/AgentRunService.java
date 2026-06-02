package cn.ethan.ai.domain.agent.service.run;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentRunRepository;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunDetailVO;
import cn.ethan.ai.domain.agent.service.IAgentRunService;
import cn.ethan.ai.domain.agent.service.execute.graph.AgentGraphRunRegistry;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 运行态查询与取消服务
 */
@Service
public class AgentRunService implements IAgentRunService {

    @Resource
    private IAgentRunRepository agentRunRepository;

    @Resource
    private AgentGraphRunRegistry agentGraphRunRegistry;

    @Override
    public AgentRunDetailVO queryRun(String runId) {
        if (StringUtils.isBlank(runId)) {
            return null;
        }
        return agentRunRepository.queryRunDetail(runId);
    }

    @Override
    public boolean cancelRun(String runId, String reason) {
        if (StringUtils.isBlank(runId)) {
            return false;
        }
        boolean cancelled = agentRunRepository.cancelRun(runId, reason);
        boolean interrupted = agentGraphRunRegistry.cancel(runId);
        return cancelled || interrupted;
    }

}
