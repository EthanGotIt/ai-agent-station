package cn.ethan.dto;

import java.util.List;

/**
 * 类型职责：表达按 Thread Sequence 游标读取的 Item 页面。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentItemPageResponseDto(
        List<AgentItemDto> items,
        long afterSequence,
        long nextAfterSequence,
        boolean hasMore
) {
    public AgentItemPageResponseDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
