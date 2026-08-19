status: completed
updated: 2026-08-20

# Commerce Guardian Agent 交接

## 已完成

- 已建立 Thread、Turn、Item、ContextSnapshot、QuestionCard 和 ExternalActionCommand 核心模型及 MyBatis-Plus 存储。
- 已切换 Spring AI 协调器、确定性 Workflow、FIFO/取消/超时、SSE 和 React + TypeScript Thread 工作区。
- 已删除旧业务聚合、旧入口、旧框架依赖和增量 SQL；唯一基线为 `docs/dev-ops/mysql/sql/commerce-guardian-agent.sql`。
- 已移除前端旧 Memory、Review、场景启动器和 Intervention 样式，仅保留 Thread 工作区样式。
- 已将 Git 提交守则与项目、文档命名规范写入 `AGENTS.md`，架构与文档统一使用 Commerce Guardian Agent 名称。

## 最近验证

- `mvn -pl commerce-guardian-agent-app -am -DskipTests compile`：通过。
- `commerce-guardian-agent-console/npm run typecheck`、`npm test -- --run`、`npm run test:e2e`、`npm run build`：通过。

## 最终验证

```text
python -m scripts.convention_check
python -m unittest discover -s scripts/tests -p "test_*.py"
mvn clean '-DskipTests=false' test
cd commerce-guardian-agent-console; npm run typecheck; npm test -- --run; npm run test:e2e; npm run build
```

规范检查、脚本单测、Maven 全量测试、前端类型检查、组件测试、轻量端到端测试和生产构建均已通过。`python -m scripts.acceptance` 需要在已启动并连接 MySQL/模型的环境中运行。
