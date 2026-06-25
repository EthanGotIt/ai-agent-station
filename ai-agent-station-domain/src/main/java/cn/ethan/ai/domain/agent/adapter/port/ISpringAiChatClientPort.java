package cn.ethan.ai.domain.agent.adapter.port;

import cn.ethan.ai.domain.agent.model.entity.AgentModelCallResultEntity;
import cn.ethan.ai.domain.agent.model.entity.AgentRunTraceEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientHarnessConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiClientTypeEnumVO;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;
import java.util.Map;

/**
 * Spring AI ChatClient 调用网关。
 */
public interface ISpringAiChatClientPort {

    AgentModelCallResultEntity call(Map<String, AiAgentClientHarnessConfigVO> harnessConfigMap,
                                    ExecuteCommandEntity command,
                                    AgentRunTraceEntity trace,
                                    String prompt,
                                    String eventType,
                                    String stepId,
                                    Integer step,
                                    List<Advisor> advisors,
                                    Map<String, Object> advisorParams,
                                    AiClientTypeEnumVO... clientTypes);

    /**
     * 基于 MCP 配置构建工具调用 Advisor，工具经 ToolGuardPolicy 过滤并包装为 GuardedToolCallback。
     */
    Advisor buildToolCallingAdvisor(Map<String, AiAgentClientHarnessConfigVO> harnessConfigMap);
}
