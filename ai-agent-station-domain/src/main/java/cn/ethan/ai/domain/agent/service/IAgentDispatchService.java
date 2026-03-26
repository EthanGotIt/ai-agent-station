package cn.ethan.ai.domain.agent.service;

import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * Agent 策略调度器接口
 */
public interface IAgentDispatchService {

    void dispatch(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception;

}
