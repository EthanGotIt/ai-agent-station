package cn.ethan.ai.trigger.http.adapter;

import cn.ethan.ai.domain.agent.adapter.port.IAgentStreamPort;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 基于 Spring MVC SseEmitter 的 SSE 流式输出适配器。
 */
@Slf4j
public class SseEmitterStreamPort implements IAgentStreamPort {

    private final SseEmitter emitter;

    public SseEmitterStreamPort(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void send(AgentExecuteResultEntity result) {
        try {
            emitter.send(SseEmitter.event()
                    .name(resolveEventName(result))
                    .data(JSON.toJSONString(result)));
        } catch (Exception e) {
            log.error("发送 SSE 事件失败：{}", e.getMessage(), e);
        }
    }

    @Override
    public void complete() {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("完成 SSE 流式输出失败：{}", e.getMessage(), e);
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

    private String resolveEventName(AgentExecuteResultEntity result) {
        if (result != null && StringUtils.isNotBlank(result.getSubType())) {
            return result.getSubType();
        }
        if (result != null && StringUtils.isNotBlank(result.getType())) {
            return result.getType();
        }
        return "message";
    }
}
