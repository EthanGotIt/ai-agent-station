package cn.ethan.infrastructure.commerce.order.persistence;

import cn.ethan.infrastructure.commerce.order.persistence.DemoOrderItemEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 演示订单商品 Mapper。
 *
 * @author ethan
 * @date 2026-08-10
 */
@Mapper
public interface DemoOrderItemMapper extends BaseMapper<DemoOrderItemEntity> {
}
