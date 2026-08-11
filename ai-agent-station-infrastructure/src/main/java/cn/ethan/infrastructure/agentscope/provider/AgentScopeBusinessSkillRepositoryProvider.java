package cn.ethan.infrastructure.agentscope.provider;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;

import java.io.IOException;
import java.util.List;

/**
 * AgentScope 业务 Skill 仓库提供器：构造并验证随应用包发布的唯一只读 Skill。
 *
 * @author ethan
 * @date 2026-08-11
 */
public final class AgentScopeBusinessSkillRepositoryProvider {

    private static final String SKILL_REPOSITORY_PATH = "agentscope/skills";
    private static final String BUSINESS_SKILL_NAME = "agent-station-business-orchestration";
    private static final String BUSINESS_SKILL_VERSION = "v1";

    private AgentScopeBusinessSkillRepositoryProvider() {
    }

    public static AgentSkillRepository loadReadonlyRepository() {
        try {
            AgentSkillRepository repository = new ClasspathSkillRepository(
                    SKILL_REPOSITORY_PATH,
                    "ai-agent-station"
            );
            try {
                validate(repository);
                return repository;
            } catch (RuntimeException invalidRepository) {
                repository.close();
                throw invalidRepository;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("AgentScope business Skill repository cannot be loaded", exception);
        }
    }

    static void validate(AgentSkillRepository repository) {
        if (repository == null || repository.isWriteable()
                || !repository.getAllSkillNames().equals(List.of(BUSINESS_SKILL_NAME))) {
            throw new IllegalStateException("AgentScope business Skill repository is invalid");
        }
        AgentSkill skill = repository.getSkill(BUSINESS_SKILL_NAME);
        if (skill == null
                || skill.getSkillContent() == null
                || skill.getSkillContent().isBlank()
                || !BUSINESS_SKILL_VERSION.equals(String.valueOf(skill.getMetadataValue("version")))) {
            throw new IllegalStateException("AgentScope business Skill is missing or invalid");
        }
    }
}
