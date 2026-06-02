package cn.ethan.ai.domain.agent.service.execute.graph;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 维护当前进程内正在执行的 Graph Run，供取消接口中断运行图。
 */
@Service
public class AgentGraphRunRegistry {

    private final Map<String, ActiveGraphRun> activeRuns = new ConcurrentHashMap<>();

    public void register(String runId, ReactAgent agent, RunnableConfig config) {
        activeRuns.put(runId, new ActiveGraphRun(agent, config, new AtomicBoolean(false)));
    }

    public void unregister(String runId) {
        activeRuns.remove(runId);
    }

    public boolean cancel(String runId) {
        ActiveGraphRun activeRun = activeRuns.get(runId);
        if (activeRun == null) {
            return false;
        }
        activeRun.cancelled().set(true);
        activeRun.agent().interrupt(activeRun.config());
        return true;
    }

    public boolean isCancelled(String runId) {
        ActiveGraphRun activeRun = activeRuns.get(runId);
        return activeRun != null && activeRun.cancelled().get();
    }

    private record ActiveGraphRun(ReactAgent agent, RunnableConfig config, AtomicBoolean cancelled) {
    }

}
