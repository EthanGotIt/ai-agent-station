package cn.ethan.infrastructure.after_sales.mapper;

import cn.ethan.infrastructure.after_sales.entity.DemoRefundCommandEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 演示退款命令 Mapper：访问带唯一幂等键的退款命令表。
 *
 * @author ethan
 * @date 2026-08-07
 */
@Mapper
public interface DemoRefundCommandMapper extends BaseMapper<DemoRefundCommandEntity> {
}
