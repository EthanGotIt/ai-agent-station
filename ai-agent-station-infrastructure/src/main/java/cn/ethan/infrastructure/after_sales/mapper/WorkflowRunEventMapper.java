package cn.ethan.infrastructure.after_sales.mapper;

import cn.ethan.infrastructure.after_sales.entity.WorkflowRunEventEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Workflow 运行事件 Mapper：保存状态审计事件。
 *
 * @author ethan
 * @date 2026-08-07
 */
@Mapper
public interface WorkflowRunEventMapper extends BaseMapper<WorkflowRunEventEntity> {
}
