package cn.ethan.ai.test.infrastructure;

import cn.ethan.ai.config.AiAgentVectorStoreProperties;
import cn.ethan.ai.domain.agent.model.valobj.AiClientApiVO;
import cn.ethan.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.ethan.ai.infrastructure.dao.po.AiClientApi;
import cn.ethan.ai.infrastructure.dao.po.AiClientToolMcp;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;

public class SensitiveConfigToStringTest {

    @Test
    public void shouldExcludeSensitiveValuesFromToString() {
        assertNotExposed(AiClientApi.builder().apiKey("api-secret").build(), "api-secret");
        assertNotExposed(AiClientApiVO.builder().apiKey("api-vo-secret").build(), "api-vo-secret");
        assertNotExposed(AiClientToolMcp.builder().transportConfig("transport-secret").build(), "transport-secret");

        AiClientToolMcpVO mcpVO = AiClientToolMcpVO.builder()
                .transportConfig("transport-vo-secret")
                .transportConfigStdio(AiClientToolMcpVO.TransportConfigStdio.builder()
                        .env(Map.of("TOKEN", "stdio-secret"))
                        .build())
                .transportConfigStreamableHttp(AiClientToolMcpVO.TransportConfigStreamableHttp.builder()
                        .headers(Map.of("Authorization", "http-secret"))
                        .build())
                .build();
        assertNotExposed(mcpVO, "transport-vo-secret");
        assertNotExposed(mcpVO, "stdio-secret");
        assertNotExposed(mcpVO, "http-secret");

        AiAgentVectorStoreProperties vectorStoreProperties = new AiAgentVectorStoreProperties();
        vectorStoreProperties.setApiKey("vector-secret");
        assertNotExposed(vectorStoreProperties, "vector-secret");
    }

    private void assertNotExposed(Object value, String secret) {
        Assert.assertFalse(value.toString().contains(secret));
    }
}
