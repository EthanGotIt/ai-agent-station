package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AiAgentFlowConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IAiAgentFlowConfigDao {

    /**
     * 插入智能体-客户端关联配置
     * @param aiAgentFlowConfig 智能体-客户端关联配置对象
     * @return 影响行数
     */
    int insert(AiAgentFlowConfig aiAgentFlowConfig);

    /**
     * 根据ID更新智能体-客户端关联配置
     * @param aiAgentFlowConfig 智能体-客户端关联配置对象
     * @return 影响行数
     */
    int updateById(AiAgentFlowConfig aiAgentFlowConfig);

    /**
     * 根据ID删除智能体-客户端关联配置
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 根据智能体ID删除关联配置
     * @param agentId 智能体ID
     * @return 影响行数
     */
    int deleteByAgentId(@Param("agentId") String agentId);

    /**
     * 根据ID查询智能体-客户端关联配置
     * @param id 主键ID
     * @return 智能体-客户端关联配置对象
     */
    AiAgentFlowConfig queryById(Long id);

    /**
     * 根据智能体ID查询关联配置列表
     * @param agentId 智能体ID
     * @return 智能体-客户端关联配置列表
     */
    List<AiAgentFlowConfig> queryByAgentId(@Param("agentId") String agentId);

    /**
     * 根据客户端ID查询关联配置列表
     * @param clientId 客户端ID
     * @return 智能体-客户端关联配置列表
     */
    List<AiAgentFlowConfig> queryByClientId(@Param("clientId") String clientId);

    /**
     * 根据智能体ID和客户端ID查询关联配置
     * @param agentId 智能体ID
     * @param clientId 客户端ID
     * @return 智能体-客户端关联配置对象
     */
    AiAgentFlowConfig queryByAgentIdAndClientId(@Param("agentId") String agentId, @Param("clientId") String clientId);

    /**
     * 查询所有智能体-客户端关联配置
     * @return 智能体-客户端关联配置列表
     */
    List<AiAgentFlowConfig> queryAll();

}
