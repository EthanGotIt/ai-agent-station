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
import cn.ethan.ai.domain.agent.model.valobj.enums.EvidenceSourceTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.harness.RuntimeToolCapabilityService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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

        ToolRoutingDecisionVO decision = service.routeForEvidenceSource(harnessConfigMap(), EvidenceSourceTypeEnumVO.OFFICIAL_DOCS);

        Assertions.assertTrue(decision.isEnabled());
        Assertions.assertTrue(decision.getAllowedToolNames().contains("get-library-docs"));
        Assertions.assertFalse(decision.getAllowedToolNames().contains("web_search_exa"));
        Assertions.assertFalse(decision.getAllowedToolNames().contains("notify_task_complete"));
        Assertions.assertEquals(1, decision.getSelectedTools().size());
    }

    @Test
    public void shouldDisableToolsForProjectKnowledgeSource() throws Exception {
        RuntimeToolCapabilityService service = new RuntimeToolCapabilityService();
        injectRepository(service, new StubRepository(List.of(
                buildMcp("5002", "exa-search", "streamable_http", List.of("web_search_exa")),
                buildMcp("5005", "notify-service", "stdio", List.of("notify_task_complete"))
        )));

        ToolRoutingDecisionVO decision = service.routeForEvidenceSource(harnessConfigMap(), EvidenceSourceTypeEnumVO.PROJECT_KNOWLEDGE);

        Assertions.assertFalse(decision.isEnabled());
        Assertions.assertTrue(decision.getAllowedToolNames().isEmpty());
    }

    @Test
    public void shouldBlockDangerousToolButKeepSafeToolFromSameMcp() throws Exception {
        RuntimeToolCapabilityService service = new RuntimeToolCapabilityService();
        injectRepository(service, new StubRepository(List.of(
                buildMcp("5006", "mixed-search-shell", "stdio", List.of("web_search_exa", "execute_shell"))
        )));

        ToolRoutingDecisionVO decision = service.routeForEvidenceSource(harnessConfigMap(), EvidenceSourceTypeEnumVO.WEB_RESEARCH);

        Assertions.assertTrue(decision.isEnabled());
        Assertions.assertTrue(decision.getAllowedToolNames().contains("web_search_exa"));
        Assertions.assertFalse(decision.getAllowedToolNames().contains("execute_shell"));
        Assertions.assertTrue(decision.getBlockedToolNames().contains("execute_shell"));
        Assertions.assertTrue(decision.getBlockedToolReasons().get("execute_shell").contains("Tool Guard"));
    }

    @Test
    public void shouldDisableToolRoutingWhenOnlyDangerousToolsRemain() throws Exception {
        RuntimeToolCapabilityService service = new RuntimeToolCapabilityService();
        injectRepository(service, new StubRepository(List.of(
                buildMcp("5007", "shell-tools", "stdio", List.of("execute_shell", "delete_file"))
        )));

        ToolRoutingDecisionVO decision = service.routeForEvidenceSource(harnessConfigMap(), EvidenceSourceTypeEnumVO.WEB_RESEARCH);

        Assertions.assertFalse(decision.isEnabled());
        Assertions.assertTrue(decision.getAllowedToolNames().isEmpty());
        Assertions.assertTrue(decision.getBlockedToolNames().contains("execute_shell"));
        Assertions.assertTrue(decision.getBlockedToolNames().contains("delete_file"));
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

        @Override
        public List<String> queryRagIdsByClientIds(List<String> clientIds) {
            return List.of();
        }
    }
}

