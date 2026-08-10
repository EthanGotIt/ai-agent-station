package cn.ethan.core.agent.port;

import cn.ethan.core.agent.model.AgentMemoryCandidateModel;
import cn.ethan.core.agent.model.AgentMemoryExtractionInputModel;

import java.util.List;

/**
 * 记忆提取端口：基础设施负责模型调用，Core 只接收经过 Schema 约束的候选。
 *
 * @author ethan
 * @date 2026-08-10
 */
public interface AgentMemoryExtractionProvider {

    List<AgentMemoryCandidateModel> extract(List<AgentMemoryExtractionInputModel> inputs);
}
