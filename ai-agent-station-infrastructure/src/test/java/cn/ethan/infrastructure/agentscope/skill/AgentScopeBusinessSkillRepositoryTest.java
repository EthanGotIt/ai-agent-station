package cn.ethan.infrastructure.agentscope.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentScope 业务 Skill 仓库测试：验证应用包内唯一、只读的编排指导可被 Classpath 解析。
 *
 * @author ethan
 * @date 2026-08-11
 */
class AgentScopeBusinessSkillRepositoryTest {

    @Test
    void loadsOnlyTheVersionedReadOnlyBusinessSkillFromClasspath() throws Exception {
        try (ClasspathSkillRepository repository = new ClasspathSkillRepository(
                "agentscope/skills",
                "test-agent-station"
        )) {
            assertEquals(List.of("agent-station-business-orchestration"), repository.getAllSkillNames());
            assertFalse(repository.isWriteable());

            AgentSkill skill = repository.getSkill("agent-station-business-orchestration");

            assertNotNull(skill);
            assertEquals("agent-station-business-orchestration", skill.getName());
            assertEquals("agent-station-business-orchestration_test-agent-station", skill.getSkillId());
            assertEquals("v1", skill.getMetadataValue("version"));
            assertTrue(skill.getResources().isEmpty());
            assertTrue(skill.getSkillContent().contains("list_recent_orders"));
            assertTrue(skill.getSkillContent().contains("get_order_snapshot"));
            assertTrue(skill.getSkillContent().contains("get_logistics_trace"));
            assertTrue(skill.getSkillContent().contains("get_after_sales_status"));
            assertTrue(skill.getSkillContent().contains("get_after_sales_policy"));
            assertTrue(skill.getSkillContent().contains("save_session_preference"));
            assertTrue(skill.getSkillContent().contains("不得猜测订单号"));
            assertTrue(skill.getSkillContent().contains("确定性 Workflow"));
        }
    }
}
