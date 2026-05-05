package cn.ethan.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Map;

/**
 * 模型调用结果，包含内容与可选元数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentModelCallResultEntity {

    private String content;

    @Builder.Default
    private Map<String, Object> metadata = Collections.emptyMap();
}

