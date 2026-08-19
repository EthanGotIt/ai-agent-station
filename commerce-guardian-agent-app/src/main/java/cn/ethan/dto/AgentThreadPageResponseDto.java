package cn.ethan.dto;

import java.util.List;

/**
 * 类型职责：表达当前用户 Thread 的分页结果。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentThreadPageResponseDto(
        List<AgentThreadDto> items,
        int page,
        int size,
        long total
) {
    public AgentThreadPageResponseDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
