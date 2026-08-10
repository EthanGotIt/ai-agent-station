package cn.ethan;

import cn.ethan.controller.AgentController;
import org.junit.jupiter.api.Test;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 应用启动测试：验证 JDBC Session、Agent 入口和核心适配器可以共同完成装配。
 *
 * @author ethan
 * @date 2026-08-05
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:application;MODE=MySQL;DATABASE_TO_UPPER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.ai.openai.api-key=test-key",
                "spring.ai.session.repository.jdbc.initialize-schema=always",
                "spring.ai.session.repository.jdbc.platform=h2",
                "DASHSCOPE_API_KEY=test-key"
        }
)
class ApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startsWithJdbcSessionAndAgentEntryBeans() {
        assertNotNull(applicationContext.getBean(SessionService.class));
        assertNotNull(applicationContext.getBean(AgentController.class));

        List<String> sessionTables = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME IN ('AI_SESSION', 'AI_SESSION_EVENT') "
                        + "ORDER BY TABLE_NAME",
                String.class
        );
        assertEquals(List.of("AI_SESSION", "AI_SESSION_EVENT"), sessionTables);
    }
}
