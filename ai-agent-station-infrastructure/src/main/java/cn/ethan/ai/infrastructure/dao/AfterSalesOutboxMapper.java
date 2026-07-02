package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AfterSalesOutboxPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AfterSalesOutboxMapper {

    int insertIgnore(AfterSalesOutboxPO event);
}
