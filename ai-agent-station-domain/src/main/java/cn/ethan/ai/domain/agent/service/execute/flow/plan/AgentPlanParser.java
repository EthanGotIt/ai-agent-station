package cn.ethan.ai.domain.agent.service.execute.flow.plan;

import cn.ethan.ai.domain.agent.model.valobj.AgentPlanVO;
import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Agent 执行计划解析器
 */
@Service
public class AgentPlanParser {

    public AgentPlanVO parse(String rawText) {
        String json = extractJson(rawText);
        try {
            AgentPlanVO plan = JSON.parseObject(json, AgentPlanVO.class);
            if (plan == null) {
                throw new IllegalArgumentException("执行计划解析结果为空");
            }
            return plan;
        } catch (Exception e) {
            throw new IllegalArgumentException("执行计划 JSON 格式不合法：" + e.getMessage(), e);
        }
    }

    public String extractJson(String rawText) {
        if (StringUtils.isBlank(rawText)) {
            throw new IllegalArgumentException("执行计划内容为空");
        }

        String text = rawText.trim();
        int codeFenceStart = text.indexOf("```");
        if (codeFenceStart >= 0) {
            int contentStart = text.indexOf('\n', codeFenceStart);
            int contentEnd = text.indexOf("```", contentStart + 1);
            if (contentStart >= 0 && contentEnd > contentStart) {
                text = text.substring(contentStart + 1, contentEnd).trim();
            }
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("模型输出中未找到 JSON 对象");
        }
        return text.substring(start, end + 1);
    }
}
