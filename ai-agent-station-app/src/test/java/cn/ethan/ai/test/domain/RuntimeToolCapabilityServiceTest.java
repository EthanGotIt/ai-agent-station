package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientHarnessConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientApiVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientModelVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientSystemPromptVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.service.execute.harness.RuntimeToolCapabilityService;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

public class RuntimeToolCapabilityServiceTest {

    @Test
    public void shouldSelectDocsAndSearchToolsForResearchTask() throws Exception {
        RuntimeToolCapabilityService service = new RuntimeToolCapabilityService();
        injectRepository(service, new StubRepository(List.of(
                buildMcp("5001", "context7-docs", "stdio", List.of("resolve-library-id", "get-library-docs")),
                buildMcp("5002", "exa-search", "streamable_http", List.of("web_search_exa", "web_fetch_exa")),
                buildMcp("5005", "notify-service", "stdio", List.of("notify_task_complete", "send_notification"))
        )));

        ToolRoutingDecisionVO decision = service.routeTools(harnessConfigMap(), "请检索 Spring AI 文档，完成后通知我");

        Assert.assertTrue(decision.isEnabled());
        Assert.assertTrue(decision.getAllowedToolNames().contains("web_search_exa"));
        Assert.assertFalse(decision.getAllowedToolNames().contains("notify_task_complete"));
        Assert.assertEquals(2, decision.getSelectedTools().size());
    }

    @Test
    public void shouldDisableToolsForSimpleWritingTask() throws Exception {
        RuntimeToolCapabilityService service = new RuntimeToolCapabilityService();
        injectRepository(service, new StubRepository(List.of(
                buildMcp("5002", "exa-search", "streamable_http", List.of("web_search_exa")),
                buildMcp("5005", "notify-service", "stdio", List.of("notify_task_complete"))
        )));

        ToolRoutingDecisionVO decision = service.routeTools(harnessConfigMap(), "帮我润色这段项目描述");

        Assert.assertFalse(decision.isEnabled());
        Assert.assertTrue(decision.getAllowedToolNames().isEmpty());
    }

    @Test
    public void shouldBlockDangerousToolButKeepSafeToolFromSameMcp() throws Exception {
        RuntimeToolCapabilityService service = new RuntimeToolCapabilityService();
        injectRepository(service, new StubRepository(List.of(
                buildMcp("5006", "mixed-search-shell", "stdio", List.of("web_search_exa", "execute_shell"))
        )));

        ToolRoutingDecisionVO decision = service.routeTools(harnessConfigMap(), "请联网搜索 Spring AI MCP 文档");

        Assert.assertTrue(decision.isEnabled());
        Assert.assertTrue(decision.getAllowedToolNames().contains("web_search_exa"));
        Assert.assertFalse(decision.getAllowedToolNames().contains("execute_shell"));
        Assert.assertTrue(decision.getBlockedToolNames().contains("execute_shell"));
        Assert.assertTrue(decision.getBlockedToolReasons().get("execute_shell").contains("Tool Guard"));
    }

    @Test
    public void shouldDisableToolRoutingWhenOnlyDangerousToolsRemain() throws Exception {
        RuntimeToolCapabilityService service = new RuntimeToolCapabilityService();
        injectRepository(service, new StubRepository(List.of(
                buildMcp("5007", "shell-tools", "stdio", List.of("execute_shell", "delete_file"))
        )));

        ToolRoutingDecisionVO decision = service.routeTools(harnessConfigMap(), "请执行系统命令");

        Assert.assertFalse(decision.isEnabled());
        Assert.assertTrue(decision.getAllowedToolNames().isEmpty());
        Assert.assertTrue(decision.getBlockedToolNames().contains("execute_shell"));
        Assert.assertTrue(decision.getBlockedToolNames().contains("delete_file"));
    }

    private void injectRepository(RuntimeToolCapabilityService service, IAgentRepository repository) throws Exception {
        Field field = RuntimeToolCapabilityService.class.getDeclaredField("repository");
        field.setAccessible(true);
        field.set(service, repository);
    }

    private Map<String, AiAgentClientHarnessConfigVO> harnessConfigMap() {
        return Map.of(
                "DEFAULT", AiAgentClientHarnessConfigVO.builder()
                        .clientId("2103")
                        .clientName("执行客户端")
                        .clientType("DEFAULT")
                        .sequence(1)
                        .build()
        );
    }

    private AiClientToolMcpVO buildMcp(String mcpId, String mcpName, String transportType, List<String> toolNames) {
        return AiClientToolMcpVO.builder()
                .mcpId(mcpId)
                .mcpName(mcpName)
                .transportType(transportType)
                .toolNames(toolNames)
                .build();
    }

    private record StubRepository(List<AiClientToolMcpVO> mcpTools) implements IAgentRepository {

        @Override
        public List<AiClientApiVO> queryAiClientApiVOListByClientIds(List<String> clientIdList) {
            return List.of();
        }

        @Override
        public List<AiClientModelVO> queryAiClientModelVOByClientIds(List<String> clientIdList) {
            return List.of();
        }

        @Override
        public List<AiClientToolMcpVO> queryAiClientToolMcpVOByClientIds(List<String> clientIdList) {
            return mcpTools;
        }

        @Override
        public Map<String, AiClientSystemPromptVO> queryAiClientSystemPromptVOByClientIds(List<String> clientIdList) {
            return Map.of();
        }

        @Override
        public List<AiClientVO> queryAiClientVOByClientIds(List<String> clientIdList) {
            return List.of();
        }

        @Override
        public List<AiClientApiVO> queryAiClientApiVOListByModelIds(List<String> modelIdList) {
            return List.of();
        }

        @Override
        public List<AiClientModelVO> queryAiClientModelVOByModelIds(List<String> modelIdList) {
            return List.of();
        }

        @Override
        public Map<String, AiAgentClientHarnessConfigVO> queryAiAgentClientHarnessConfig(String aiAgentId) {
            return Map.of();
        }

        @Override
        public AiAgentVO queryAiAgentByAgentId(String aiAgentId) {
            return null;
        }

        @Override
        public List<AiAgentClientHarnessConfigVO> queryAiAgentClientsByAgentId(String aiAgentId) {
            return List.of();
        }
    }
}

