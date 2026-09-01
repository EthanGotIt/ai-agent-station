# Commerce Guardian Agent

Commerce Guardian Agent 是一个 Agent-first 执行平台：业务订单、物流、退款和催发货只是验证夹具，核心价值在可恢复上下文、编排边界、持久化 HITL 与可靠运行时。

四个工程亮点：

1. `Thread → Turn → Item`：Thread 是上下文根，Turn 表示一次执行，Item 是消息和轨迹的事实来源；历史按 sequence 游标恢复。
2. ReAct / Workflow 混合编排：Spring AI 协调 Agent 只调用只读工具或启动 Workflow，关键写操作由 Java 显式状态机负责。
3. QuestionCard + Checkpoint：确认、拒绝和结构化参数持久化到 MySQL，可跨刷新、断线和重启恢复。
4. Reliable Agent Runtime：同 Thread FIFO、取消、分层超时、SSE 实时投影、幂等命令、Lease、退避重试和人工恢复。
5. Explicit Agent Decision Contract：终止只接受受控 Tool 决策；缺失决策最多纠正一次，仍失败以 `AGENT_DECISION_MISSING` 安全收口，前端只允许创建新 Turn 重试。

## 模块和启动

- `commerce-guardian-agent-core`：纯 Java 领域模型、端口和 Thread Runtime，不依赖 Spring 或数据库。
- `commerce-guardian-agent-infrastructure`：MyBatis-Plus 持久化、Spring AI 协调器、订单夹具和外部动作 Worker。
- `commerce-guardian-agent-app`：Spring Boot 启动、配置和唯一 `/api/agent` HTTP/SSE 契约。
- `agent-fronted`：React + TypeScript + Vite Thread 工作区。

准备 Python 3.14、JDK 17、Maven、Node.js 24 和 MySQL 后，先执行 `docs/dev-ops/mysql/commerce-guardian-agent.sql`，再启动：

```text
mvn spring-boot:run -pl commerce-guardian-agent-app
cd agent-fronted
npm install
npm run dev
```

演示请求从创建 Thread 开始：查询 `ORDER-PAID-001`，或请求退款/催发货，确认 QuestionCard 后观察 Worker 和执行轨迹。生产身份由认证上下文提供；本地演示使用 `X-User-Id`。

## 验证

```text
python -m scripts.convention_check
python -m unittest discover -s scripts/tests -p "test_*.py"
mvn clean '-DskipTests=false' test
cd agent-fronted
npm run typecheck
npm test -- --run
npm run test:component
npm run build
```

运行中的 Agent 可用下面的命令执行 Week 4 本地验收。订单夹具与 Agent 使用不同端口和独立 SQLite；命令默认只做物流、退款幂等和催发货重试，并把删除场景保持为 gated，避免误删非一次性数据：

```text
python -m scripts.acceptance `
  --base-url http://127.0.0.1:8090 `
  --order-service-url http://127.0.0.1:18080 `
  --require-expedite-retry
```

确认夹具数据库确实为本次运行创建且可丢弃后，才额外传入 `--allow-destructive-fixture-actions`，执行一次性订单的删除、重放和 404 清理验证。验收 runner 会检查 Item 游标严格前进、刷新恢复、开放交互唯一性、Turn `clientRequestId` 幂等、执行轨迹回放、物流事件唯一性，以及订单动作的受控结果和业务变更计数。

详细模型、数据流、配置和排错分别见 [docs/architecture.md](docs/architecture.md)、[docs/runbook.md](docs/runbook.md) 与 [docs/review-runbook.md](docs/review-runbook.md)。
