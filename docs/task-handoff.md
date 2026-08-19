status: active
updated: 2026-08-19

# v3 交接

## 已完成

- 已建立 v3 Thread、Turn、Item、ContextSnapshot、QuestionCard 和 ExternalActionCommand 核心模型及 MyBatis-Plus 存储。
- 已切换 Spring AI 协调器、确定性 Workflow、FIFO/取消/超时、SSE 和 React + TypeScript Thread 工作区。
- 已删除旧业务聚合、旧入口、旧框架依赖和增量 SQL；唯一基线为 `docs/dev-ops/mysql/sql/ai-agent-station.sql`。
- 已将 Git 提交守则写入 `AGENTS.md`，架构与文档只保留 v3 说明。

## 最近验证

- `mvn -pl ai-agent-station-app -am -DskipTests compile`：通过。
- `agent-console/npm run typecheck`、`npm test -- --run`、`npm run build`：通过。

## 下一步唯一动作

运行完整验证并修复根因：

```text
python -m scripts.convention_check
python -m unittest discover -s scripts/tests -p "test_*.py"
mvn clean '-DskipTests=false' test
cd agent-console; npm run typecheck; npm test -- --run; npm run build
```

随后运行 `python -m scripts.acceptance` 验证已启动服务的端到端流程，并将本文件状态改为 `completed`。
