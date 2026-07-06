package cn.ethan.ai.api.dto;

import java.util.Map;

/**
 * 售后 Agent 运行响应。
 */
public record AfterSalesRunResponseDTO(String caseId,
                                        String turnId,
                                        String stage,
                                        String checkpointId,
                                        String nextNode,
                                        String waitingReason,
                                        String terminalReason,
                                        String commandId,
                                        Map<String, Object> state) {
}
