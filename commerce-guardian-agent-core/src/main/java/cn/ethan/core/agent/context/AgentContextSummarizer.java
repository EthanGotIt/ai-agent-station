package cn.ethan.core.agent.context;

import cn.ethan.core.agent.thread.AgentItemModel;

import java.util.List;

/**
 * 类型职责：将已完成的旧 Item 压缩为可复用摘要；实现不得执行工具或写入外部系统。
 *
 * @author ethan
 * @date 2026-08-20
 */
@FunctionalInterface
public interface AgentContextSummarizer {

    String summarize(List<AgentItemModel> items);
}
