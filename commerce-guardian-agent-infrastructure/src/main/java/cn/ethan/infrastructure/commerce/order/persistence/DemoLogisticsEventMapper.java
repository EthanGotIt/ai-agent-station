package cn.ethan.infrastructure.commerce.order.persistence;

import cn.ethan.infrastructure.commerce.order.persistence.DemoLogisticsEventEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 演示物流事件 Mapper。
 *
 * @author ethan
 * @date 2026-08-10
 */
@Mapper
public interface DemoLogisticsEventMapper extends BaseMapper<DemoLogisticsEventEntity> {
}
