package cn.ethan.dto;

import java.util.List;

/**
 * 售后申请分页 DTO：提供审核台可稳定刷新的有界列表。
 *
 * @author ethan
 * @date 2026-08-12
 */
public record AgentAfterSalesCasePageDto(List<AgentAfterSalesCaseDto> items, int page, int size, boolean hasNext) {

    public AgentAfterSalesCasePageDto {
        items = List.copyOf(items == null ? List.of() : items);
    }
}
