package cn.ethan.infrastructure.order.mapper;

import cn.ethan.infrastructure.order.entity.DemoOrderEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 演示订单 Mapper：负责访问只读的 DEMO_ORDER 表。
 *
 * @author ethan
 * @date 2026-08-05
 */
@Mapper
public interface DemoOrderMapper extends BaseMapper<DemoOrderEntity> {
}
