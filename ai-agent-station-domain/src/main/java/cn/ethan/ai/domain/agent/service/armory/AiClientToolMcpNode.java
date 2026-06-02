package cn.ethan.ai.domain.agent.service.armory;

import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.adapter.port.IMcpClientLifecyclePort;
import cn.ethan.ai.domain.agent.model.valobj.AiClientToolMcpVO;
import cn.ethan.ai.domain.agent.model.valobj.ArmoryAssemblyContextVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MCP 客户端配置登记节点。
 * 具体传输、初始化、缓存和关闭由 infrastructure 生命周期管理器负责。
 */
@Slf4j
@Service
public class AiClientToolMcpNode extends AbstractArmorySupport {

    @Resource
    private AiClientModelNode aiClientModelNode;

    @Resource
    private IMcpClientLifecyclePort mcpClientLifecyclePort;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, ArmoryAssemblyContextVO assemblyContext) throws Exception {
        log.info("智能体装配节点，MCP 工具配置：{}", JSON.toJSONString(requestParameter));

        List<AiClientToolMcpVO> aiClientToolMcpList = assemblyContext.getValue(dataName());

        if (aiClientToolMcpList == null || aiClientToolMcpList.isEmpty()) {
            log.warn("没有需要登记的 MCP 工具配置");
            return router(requestParameter, assemblyContext);
        }

        mcpClientLifecyclePort.registerConfigurations(aiClientToolMcpList);

        return router(requestParameter, assemblyContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, ArmoryAssemblyContextVO, String> get(ArmoryCommandEntity requestParameter, ArmoryAssemblyContextVO assemblyContext) throws Exception {
        return aiClientModelNode;
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_TOOL_MCP.getDataName();
    }

}
