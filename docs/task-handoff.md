status: active
updated: 2026-08-20

# Commerce Guardian Agent 交接

## 当前状态

- 已建立 Agent-first 分包守则，完成 Core、Infrastructure 和 App 的能力内聚迁移。
- 已拆分 Thread、Turn、Item、QuestionCard、ContextSnapshot 存储端口，并保持 MyBatis-Plus 适配器可构建。
- 已将 SSE 事件总线移到 App `agent.stream`，Core 仅保留事件发布和订阅端口。
- 已移除无调用的订单分析、商品明细和近期订单分支，以及对应的直接依赖。
- 唯一 SQL 基线为 `docs/dev-ops/mysql/commerce-guardian-agent.sql`；前端目录为 `agent-console`。

## 最近验证

- `python -m scripts.convention_check`：通过。
- `mvn -pl commerce-guardian-agent-app -am -DskipTests compile`：通过。

## 下一步唯一动作

补齐 SQL 基线与前端目录相关文档后，执行 Python、Maven 和 `agent-console` 全量验证，
再按阶段检查 staged diff 并提交测试与文档收尾。
