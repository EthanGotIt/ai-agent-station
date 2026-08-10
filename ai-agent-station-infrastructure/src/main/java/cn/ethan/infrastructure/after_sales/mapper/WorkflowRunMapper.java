package cn.ethan.infrastructure.after_sales.mapper;

import cn.ethan.infrastructure.after_sales.entity.WorkflowRunEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Workflow 运行 Mapper：复用 MyBatis-Plus CRUD 与乐观锁条件更新。
 *
 * @author ethan
 * @date 2026-08-07
 */
@Mapper
public interface WorkflowRunMapper extends BaseMapper<WorkflowRunEntity> {
}
