# Durable After-Sales Agent

一个受控、可恢复的售后退款 Agent。模型只负责信息收集规划；Java 负责校验、执行只读证据步骤、状态推进、人工审批、幂等退款和故障恢复。

当前主线采用 Spring AI、Spring State Machine、MyBatis 与 MySQL，不定位为通用多 Agent 或工作流平台。

## 主链路

```text
INTAKE
  Plan -> first approved step -> RePlan (at most 3)
  missing information -> NEED_USER_INPUT interrupt
  eligible -> PENDING_APPROVAL
PENDING_APPROVAL
  APPROVE -> idempotent refund -> COMPLETED
  REJECT / ineligible -> REJECTED
```

状态机只维护 `INTAKE`、`PENDING_APPROVAL`、`COMPLETED`、`REJECTED` 四个业务状态。`caseId` 是售后业务标识与状态机 thread key；`turnId` 是一次 start/resume 尝试；Case 指向的边界 checkpoint 是唯一的外部恢复位置。

## 文档

- [文档导航](docs/README.md)
- [当前架构与边界](docs/architecture.md)
- [运行、配置与 HTTP 验收](docs/after-sales-agent.md)
- [面试说明](docs/interview-defense.md)
- [评测结果](docs/evaluation/after-sales-live-evaluation.md)

## 快速验收

```powershell
mvn -pl ai-agent-station-app -am "-DskipTests=false" test
```

Testcontainers MySQL 集成验收要求 Docker Desktop 已启动：

```powershell
mvn -pl ai-agent-station-app -am "-DskipTests=false" "-Dit.test=MysqlAfterSalesPersistenceIT" verify
```

数据库初始化脚本位于 [ai-agent-station.sql](docs/dev-ops/mysql/sql/ai-agent-station.sql)。
