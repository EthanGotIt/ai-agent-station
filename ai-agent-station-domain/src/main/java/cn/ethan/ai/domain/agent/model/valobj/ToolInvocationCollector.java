package cn.ethan.ai.domain.agent.model.valobj;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 通过 Spring AI ToolContext 传递的请求级工具调用收集器。
 */
public class ToolInvocationCollector {

    public static final String TOOL_CONTEXT_KEY = "ai_agent_tool_invocation_collector";

    public static final String METADATA_KEY = "qa_tool_invocations";

    private final List<ToolInvocationRecordVO> records = Collections.synchronizedList(new ArrayList<>());

    public void add(ToolInvocationRecordVO record) {
        if (record != null) {
            records.add(record);
        }
    }

    public List<ToolInvocationRecordVO> snapshot() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }
}
