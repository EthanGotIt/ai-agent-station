package cn.ethan.infrastructure.commerce.order.persistence;

import cn.ethan.infrastructure.commerce.order.persistence.DemoOrderEntity;
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
