package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentRuntimeConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientApiVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientModelVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientSystemPromptVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.service.execute.graph.RuntimeToolCapabilityService;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

public class RuntimeToolCapabilityServiceTest {

    @Test
    public void shouldSelectSearchAndNotifyToolsForResearchTask() throws Exception {
        RuntimeToolCapabilityService service = serviceWith(List.of(
                buildMcp("5001", "context7-docs", List.of("resolve-library-id", "get-library-docs")),
                buildMcp("5002", "exa-search", List.of("web_search_exa", "web_fetch_exa")),
                buildMcp("5005", "windows-notify", List.of("notify_task_complete", "send_notification"))
        ));

        ToolRoutingDecisionVO decision = service.routeTools(List.of("2103"), "请检索 Spring AI 文档，完成后通知我");

        Assert.assertTrue(decision.isEnabled());
        Assert.assertTrue(decision.getAllowedToolNames().contains("web_search_exa"));
        Assert.assertTrue(decision.getAllowedToolNames().contains("notify_task_complete"));
        Assert.assertEquals(3, decision.getSelectedTools().size());
    }

    @Test
    public void shouldDisableToolsForSimpleWritingTask() throws Exception {
        RuntimeToolCapabilityService service = serviceWith(List.of(
                buildMcp("5002", "exa-search", List.of("web_search_exa"))
        ));

        ToolRoutingDecisionVO decision = service.routeTools(List.of("2103"), "帮我润色这段项目描述");

        Assert.assertFalse(decision.isEnabled());
        Assert.assertTrue(decision.getAllowedToolNames().isEmpty());
    }

    @Test
    public void shouldBlockDangerousToolButKeepSafeToolFromSameMcp() throws Exception {
        RuntimeToolCapabilityService service = serviceWith(List.of(
                buildMcp("5006", "mixed-search-shell", List.of("web_search_exa", "execute_shell"))
        ));

        ToolRoutingDecisionVO decision = service.routeTools(List.of("2103"), "请联网搜索 Spring AI MCP 文档");

        Assert.assertTrue(decision.isEnabled());
        Assert.assertTrue(decision.getAllowedToolNames().contains("web_search_exa"));
        Assert.assertFalse(decision.getAllowedToolNames().contains("execute_shell"));
        Assert.assertTrue(decision.getBlockedToolNames().contains("execute_shell"));
    }

    @Test
    public void shouldDisableToolRoutingWhenOnlyDangerousToolsRemain() throws Exception {
        RuntimeToolCapabilityService service = serviceWith(List.of(
                buildMcp("5007", "shell-tools", List.of("execute_shell", "delete_file"))
        ));

        ToolRoutingDecisionVO decision = service.routeTools(List.of("2103"), "请执行系统命令");

        Assert.assertFalse(decision.isEnabled());
        Assert.assertTrue(decision.getBlockedToolNames().contains("execute_shell"));
        Assert.assertTrue(decision.getBlockedToolNames().contains("delete_file"));
    }

    private RuntimeToolCapabilityService serviceWith(List<AiClientToolMcpVO> tools) throws Exception {
        RuntimeToolCapabilityService service = new RuntimeToolCapabilityService();
        Field field = RuntimeToolCapabilityService.class.getDeclaredField("repository");
        field.setAccessible(true);
        field.set(service, new StubRepository(tools));
        return service;
    }

    private AiClientToolMcpVO buildMcp(String mcpId, String name, List<String> toolNames) {
        return AiClientToolMcpVO.builder()
                .mcpId(mcpId)
                .mcpName(name)
                .transportType("stdio")
                .toolNames(toolNames)
                .build();
    }

    private record StubRepository(List<AiClientToolMcpVO> tools) implements IAgentRepository {

        @Override
        public List<AiClientApiVO> queryAiClientApiVOListByClientIds(List<String> ids) {
            return List.of();
        }

        @Override
        public List<AiClientModelVO> queryAiClientModelVOByClientIds(List<String> ids) {
            return List.of();
        }

        @Override
        public List<AiClientToolMcpVO> queryAiClientToolMcpVOByClientIds(List<String> ids) {
            return tools;
        }

        @Override
        public Map<String, AiClientSystemPromptVO> queryAiClientSystemPromptVOByClientIds(List<String> ids) {
            return Map.of();
        }

        @Override
        public List<AiClientVO> queryAiClientVOByClientIds(List<String> ids) {
            return List.of();
        }

        @Override
        public List<AiClientApiVO> queryAiClientApiVOListByModelIds(List<String> ids) {
            return List.of();
        }

        @Override
        public List<AiClientModelVO> queryAiClientModelVOByModelIds(List<String> ids) {
            return List.of();
        }

        @Override
        public AiAgentVO queryAiAgentByAgentId(String agentId) {
            return null;
        }

        @Override
        public AiAgentRuntimeConfigVO queryAiAgentRuntimeConfig(String agentId) {
            return null;
        }
    }

}
