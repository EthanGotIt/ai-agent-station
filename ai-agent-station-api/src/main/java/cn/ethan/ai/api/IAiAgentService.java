package cn.ethan.ai.api;

import cn.ethan.ai.api.dto.AgentExecuteRequestDTO;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * Ai Agent 服务接口
 */
public interface IAiAgentService {

    ResponseBodyEmitter execute(AgentExecuteRequestDTO request, HttpServletResponse response);

}
