# 文档导航

## 当前文档

- [architecture.md](architecture.md)：项目定位、运行时边界、恢复语义和后续生产联调方向。
- [after-sales-agent.md](after-sales-agent.md)：本地配置、HTTP 调用、数据库初始化和验收命令。
- [interview-defense.md](interview-defense.md)：面试表达、可证明能力与不可宣称边界。

## 评测与数据

- [真实模型冻结轨迹](evaluation/after-sales-live-evaluation.md)：显式开启的真实模型规划评测与历史记录。
- [并发基线](evaluation/after-sales-java17-benchmark.md)：内存 Runtime 的并发基线，不代表生产容量。
- [Java 21 与 Redis 升级评估](decisions/java21-redis-evaluation.md)：兼容性、基准、Redis 前置条件与当前决策。
- [MySQL 初始化脚本](dev-ops/mysql/sql/ai-agent-station.sql)：业务审计、售后与 Session Memory 的统一建表脚本。

## 历史资料

- [重构计划](history/refactoring-plan.md)：从 LangGraph4j 迁移到当前运行时的历史计划与阶段记录，不作为当前设计说明。
