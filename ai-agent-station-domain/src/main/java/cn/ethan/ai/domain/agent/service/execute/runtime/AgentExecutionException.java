package cn.ethan.ai.domain.agent.service.execute.runtime;

import lombok.Getter;

/**
 * Agent 执行异常，附带 runId 便于回写和流式输出。
 */
@Getter
public class AgentExecutionException extends RuntimeException {

    private final String runId;

    public AgentExecutionException(String runId, String message, Throwable cause) {
        super(message, cause);
        this.runId = runId;
    }

}
