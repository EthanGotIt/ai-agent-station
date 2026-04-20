package cn.ethan.ai.trigger.http;

import cn.ethan.ai.api.IAiAgentService;
import cn.ethan.ai.api.dto.AgentExecuteRequestDTO;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.StreamTransportTypeEnumVO;
import cn.ethan.ai.domain.agent.service.IAgentDispatchService;
import cn.ethan.ai.trigger.http.adapter.ResponseBodyEmitterStreamPort;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * Agent 统一执行入口
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class AiAgentController implements IAiAgentService {

    @Resource
    private IAgentDispatchService agentDispatchService;

    @Override
    @RequestMapping(value = "execute", method = RequestMethod.POST)
    public ResponseBodyEmitter execute(@RequestBody AgentExecuteRequestDTO request, HttpServletResponse response) {
        log.info("Agent 流式执行请求开始，请求信息：{}", JSON.toJSONString(request));

        try {
            response.setContentType("application/x-ndjson");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache, no-transform");
            response.setHeader("Connection", "keep-alive");

            validateRequest(request);

            ResponseBodyEmitter emitter = new ResponseBodyEmitter(10 * 60 * 1000L);
            ExecuteCommandEntity executeCommandEntity = ExecuteCommandEntity.builder()
                    .aiAgentId(request.getAiAgentId())
                    .message(request.getMessage())
                    .sessionId(request.getSessionId())
                    .maxStep(request.getMaxStep())
                    .streamProtocol(StreamTransportTypeEnumVO.STREAMABLE_HTTP.getCode())
                    .build();

            agentDispatchService.dispatch(executeCommandEntity, new ResponseBodyEmitterStreamPort(emitter));
            return emitter;
        } catch (Exception e) {
            log.error("Agent 请求处理异常：{}", e.getMessage(), e);
            ResponseBodyEmitter errorEmitter = new ResponseBodyEmitter();
            try {
                sendError(errorEmitter, "请求处理异常：" + e.getMessage(), request);
            } catch (Exception ex) {
                log.error("发送错误信息失败：{}", ex.getMessage(), ex);
            }
            return errorEmitter;
        }
    }

    private void validateRequest(AgentExecuteRequestDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (StringUtils.isBlank(request.getAiAgentId())) {
            throw new IllegalArgumentException("aiAgentId 不能为空");
        }
        if (StringUtils.isBlank(request.getMessage())) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (StringUtils.isBlank(request.getSessionId())) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (request.getMaxStep() != null && request.getMaxStep() <= 0) {
            throw new IllegalArgumentException("maxStep 必须大于 0");
        }
    }

    private void sendError(ResponseBodyEmitter emitter, String content, AgentExecuteRequestDTO request) throws Exception {
        AgentExecuteResultEntity errorResult = AgentExecuteResultEntity.createErrorResult(
                content,
                request == null ? null : request.getSessionId(),
                null
        );
        emitter.send(JSON.toJSONString(errorResult) + "\n");
        emitter.complete();
    }
}
