package cn.ethan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgent {

    private Long id;

    private String agentId;

    private String agentName;

    private String description;

    private String channel;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
