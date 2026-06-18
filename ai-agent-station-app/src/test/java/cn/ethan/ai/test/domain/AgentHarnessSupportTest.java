package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.valobj.AgentActionVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingItemVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentActionTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.harness.AgentActionParser;
import cn.ethan.ai.domain.agent.service.execute.harness.AgentActionPolicy;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Set;

public class AgentHarnessSupportTest {

    private final AgentActionParser parser = new AgentActionParser();

    private final AgentActionPolicy policy = new AgentActionPolicy();

    @Test
    public void shouldParseFencedActionJson() {
        AgentActionVO action = parser.parse("""
                ```json
                {
                  "actionType": "RAG_RETRIEVE",
                  "query": "Spring AI MCP 如何接入",
                  "reason": "需要证据"
                }
                ```
                """, "fallback");

        Assert.assertEquals(AgentActionTypeEnumVO.RAG_RETRIEVE, action.getType());
        Assert.assertEquals("Spring AI MCP 如何接入", action.getQuery());
        Assert.assertFalse(action.hasParseError());
    }

    @Test
    public void shouldFallbackToLlmRespondWhenActionInvalid() {
        AgentActionVO action = parser.parse("not json", "请总结项目");

        Assert.assertEquals(AgentActionTypeEnumVO.LLM_RESPOND, action.getType());
        Assert.assertEquals("请总结项目", action.getQuery());
        Assert.assertTrue(action.hasParseError());
    }

    @Test
    public void shouldKeepOnlyReadOnlyEvidenceTools() {
        ToolRoutingDecisionVO original = ToolRoutingDecisionVO.builder()
                .enabled(true)
                .summary("mixed")
                .allowedToolNames(Set.of("web_search_exa", "get-library-docs", "send_notification", "create_memory"))
                .selectedMcpIds(Set.of("5001", "5002", "5005"))
                .selectedTools(List.of(
                        ToolRoutingItemVO.builder()
                                .mcpId("5001")
                                .mcpName("context7-docs")
                                .toolNames(List.of("get-library-docs"))
                                .routeTags(List.of("docs"))
                                .build(),
                        ToolRoutingItemVO.builder()
                                .mcpId("5002")
                                .mcpName("exa-search")
                                .toolNames(List.of("web_search_exa", "send_notification"))
                                .routeTags(List.of("search"))
                                .build()
                ))
                .build();

        ToolRoutingDecisionVO filtered = policy.readOnlyEvidenceDecision(original);

        Assert.assertTrue(filtered.isEnabled());
        Assert.assertTrue(filtered.getAllowedToolNames().contains("web_search_exa"));
        Assert.assertTrue(filtered.getAllowedToolNames().contains("get-library-docs"));
        Assert.assertFalse(filtered.getAllowedToolNames().contains("send_notification"));
        Assert.assertFalse(filtered.getAllowedToolNames().contains("create_memory"));
        Assert.assertTrue(filtered.getBlockedToolNames().contains("send_notification"));
    }

    @Test
    public void shouldRejectWhenActionLoopExceedsLimit() {
        AgentActionPolicy.PolicyCheckResult result = policy.validate(
                AgentActionVO.builder().type(AgentActionTypeEnumVO.LLM_RESPOND).build(),
                AgentActionPolicy.DEFAULT_MAX_ACTION_ROUNDS + 1,
                0,
                null
        );

        Assert.assertFalse(result.accepted());
        Assert.assertTrue(result.reason().contains("最大 Action Loop"));
    }
}
