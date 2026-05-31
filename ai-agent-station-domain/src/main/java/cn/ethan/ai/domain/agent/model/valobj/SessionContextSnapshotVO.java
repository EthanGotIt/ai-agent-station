package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 持久化 session 短期记忆组装结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionContextSnapshotVO {

    private String contextSummary;

    private boolean compressed;

    private int originalContextUnits;

    private int assembledContextUnits;

    private int messageCount;

    private int recentMessageCount;

    public static SessionContextSnapshotVO empty() {
        return SessionContextSnapshotVO.builder()
                .contextSummary("")
                .build();
    }

}
