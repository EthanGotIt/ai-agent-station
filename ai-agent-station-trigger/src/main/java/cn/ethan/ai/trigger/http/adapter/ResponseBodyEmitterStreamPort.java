package cn.ethan.ai.trigger.http.adapter;

import cn.ethan.ai.domain.agent.adapter.port.IAgentStreamPort;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * 基于 Spring MVC ResponseBodyEmitter 的流式输出适配器。
 */
@Slf4j
public class ResponseBodyEmitterStreamPort implements IAgentStreamPort {

    private final ResponseBodyEmitter emitter;

    public ResponseBodyEmitterStreamPort(ResponseBodyEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void send(AgentExecuteResultEntity result) {
        try {
            emitter.send(JSON.toJSONString(result) + "\n");
        } catch (Exception e) {
            log.error("发送流式结果失败：{}", e.getMessage(), e);
        }
    }

    @Override
    public void complete() {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("完成流式输出失败：{}", e.getMessage(), e);
        }
    }

    @Override
    public void onTimeout(Runnable callback) {
        emitter.onTimeout(callback);
    }

    @Override
    public void onCompletion(Runnable callback) {
        emitter.onCompletion(callback);
    }
}
