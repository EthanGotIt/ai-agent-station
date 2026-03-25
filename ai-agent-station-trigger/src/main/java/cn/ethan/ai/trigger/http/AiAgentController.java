package cn.ethan.ai.trigger.http;

import cn.ethan.ai.api.IAiAgentService;
import cn.ethan.ai.api.dto.AutoAgentRequestDTO;
import cn.ethan.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.StreamTransportTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.IExecuteStrategy;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AutoAgent 自动智能对话体
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class AiAgentController implements IAiAgentService {

    @Resource(name = "autoAgentExecuteStrategy")
    private IExecuteStrategy autoAgentExecuteStrategy;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @RequestMapping(value = "auto_agent", method = RequestMethod.POST)
    public ResponseBodyEmitter autoAgent(@RequestBody AutoAgentRequestDTO request, HttpServletResponse response) {
        log.info("AutoAgent流式执行请求开始，请求信息：{}", JSON.toJSONString(request));
        
        try {
            response.setContentType("application/x-ndjson");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache, no-transform");
            response.setHeader("Connection", "keep-alive");

            ResponseBodyEmitter emitter = new ResponseBodyEmitter(Long.MAX_VALUE);
            
            ExecuteCommandEntity executeCommandEntity = ExecuteCommandEntity.builder()
                    .aiAgentId(request.getAiAgentId())
                    .message(request.getMessage())
                    .sessionId(request.getSessionId())
                    .maxStep(request.getMaxStep())
                    .streamProtocol(StreamTransportTypeEnumVO.STREAMABLE_HTTP.getCode())
                    .build();
            
            threadPoolExecutor.execute(() -> {
                try {
                    autoAgentExecuteStrategy.execute(executeCommandEntity, emitter);
                } catch (Exception e) {
                    log.error("AutoAgent执行异常：{}", e.getMessage(), e);
                    try {
                        AutoAgentExecuteResultEntity errorResult = AutoAgentExecuteResultEntity.createErrorResult("执行异常：" + e.getMessage(), request.getSessionId());
                        emitter.send(encodeStreamResult(errorResult, StreamTransportTypeEnumVO.STREAMABLE_HTTP));
                    } catch (Exception ex) {
                        log.error("发送异常信息失败：{}", ex.getMessage(), ex);
                    }
                } finally {
                    try {
                        emitter.complete();
                    } catch (Exception e) {
                        log.error("完成流式输出失败：{}", e.getMessage(), e);
                    }
                }
            });
            
            return emitter;

        } catch (Exception e) {
            log.error("AutoAgent请求处理异常：{}", e.getMessage(), e);
            ResponseBodyEmitter errorEmitter = new ResponseBodyEmitter();
            try {
                AutoAgentExecuteResultEntity errorResult = AutoAgentExecuteResultEntity.createErrorResult("请求处理异常：" + e.getMessage(), request.getSessionId());
                errorEmitter.send(encodeStreamResult(errorResult, StreamTransportTypeEnumVO.STREAMABLE_HTTP));
                errorEmitter.complete();
            } catch (Exception ex) {
                log.error("发送错误信息失败：{}", ex.getMessage(), ex);
            }
            return errorEmitter;
        }
    }

    private String encodeStreamResult(AutoAgentExecuteResultEntity result, StreamTransportTypeEnumVO protocol) {
        String json = JSON.toJSONString(result);
        if (protocol == StreamTransportTypeEnumVO.STREAMABLE_HTTP) {
            return json + "\n";
        }
        return "data: " + json + "\n\n";
    }

}
