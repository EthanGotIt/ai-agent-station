# 文档索引

| 文档 | 用途 | 何时阅读 |
|---|---|---|
| [任务交接](task-handoff.md) | 当前基线、已验证能力与下一步唯一动作 | 恢复跨会话任务前 |
| [架构说明](architecture.md) | 队列、持久化 Workflow QuestionCard、会话记忆、双框架与提示词边界 | 调整运行内核前 |
| [执行计划与验收矩阵](execution-plan.md) | 已交付范围、边界与静态审计映射 | 评估变更范围时 |
| [V2 验收报告](acceptance/v2-20260812.md) | 非生产真实模型、MySQL、控制台与离线门禁结果 | 核对 V2 交付结论时 |
| [运行手册](runbook.md) | 环境、启动、API、故障处理与真实验收 | 本地运行或验收时 |
| [项目协作约定](../AGENTS.md) | 包结构、命名、代码风格和最低验证 | 开始代码改动前 |
| [MySQL 初始化脚本](dev-ops/mysql/sql/ai-agent-station.sql) | 创建当前数据库对象 | 初始化本地数据库时 |
| [领域 V2 升级脚本](dev-ops/mysql/sql/manual-upgrade-domain-v2.sql) | 为已有库增加商品、物流和售后申请单 | 完成备份后升级业务闭环时 |
| [记忆版本升级脚本](dev-ops/mysql/sql/manual-upgrade-memory-version.sql) | 为会话记忆增加乐观锁 `VERSION` | 完成 Memory V2 升级后执行一次 |
| [人工清理旧表脚本](dev-ops/mysql/sql/manual-cleanup-old-after-sales.sql) | 清理历史售后表 | 完成备份并人工确认后 |

文档记录稳定约束与可复现操作；代码、测试和配置是当前行为的最终依据。
