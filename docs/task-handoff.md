# 任务交接

## 状态

`completed`

更新时间：2026-08-11

## 当前基线

- V2 三模块重构已固化为提交 `8b02be3`（`feat: establish v2 agent station baseline`）；旧 API、domain、trigger、types 模块和旧售后状态机已移除，依赖方向为 `app → core`、`app → infrastructure`、`infrastructure → core`。
- Order Workflow V2 支持 `QUERY`、`TRACK`、`DIAGNOSE`，缺订单号时生成近期订单 QuestionCard，诊断缺问题类型时继续生成卡。恢复从实时订单校验节点重入，不持久化订单快照。
- After-sales Workflow V2 支持申请与状态查询：依次收集订单、原因、说明和最终确认。自动退款创建 `DEMO_AFTER_SALES_CASE + DEMO_REFUND_COMMAND`，人工审核只创建 `PENDING_REVIEW` 申请单；同用户订单申请唯一。
- ReAct 的生产目录为五读一写：近期订单、订单快照、物流、售后状态、售后规则均为 `ALLOW`，`save_session_preference` 固定 `ASK`，仅写入当前会话的受控偏好。`acceptance` 探针仅在 `acceptance` Profile 注册。
- ReAct 为单回合，`InMemoryAgentStateStore` 在结束、超时、取消、异常时清理；Workflow 以 `WORKFLOW_RUN` 持久化恢复。SSE `workflow_question` 将 QuestionCard 与运行 ID/版本作为原子事件负载发送。
- 记忆 V2 保持生成/使用分离、后台提取、人工优先与 tombstone；偏好实际注入 ReAct 的语言、格式、详略指令。`manual-upgrade-domain-v2.sql` 负责将已有库补齐商品、物流、售后申请单和退款 `CASE_ID`。
- 收口测试已补齐订单五类诊断及边界时间、持久化 QuestionCard 重建恢复，退款的多阶段收集、重复申请、资格拒绝与陈旧确认；`save_session_preference` 覆盖越权值和缺失运行时归属。React 业务卡按后端真实字段渲染金额、商品、预计送达与建议，并验证 SSE CRLF 尾部缓冲。
- 当前交付只覆盖本地开发和自动验证，不要求上线或现场演示。仓库保留的 Docker 文件不属于当前验收，也不要求下载镜像、配置 TLS 或启动 Compose。

## 最近验证

- `D:\Application\miniconda3\python.exe -m scripts.convention_check`：通过。
- `D:\Application\miniconda3\python.exe -m unittest discover -s scripts/tests -p "test_*.py"`：13 项通过。
- `D:\Application\miniconda3\python.exe -m scripts.plan_audit --strict`：23/23 通过。
- `mvn clean '-DskipTests=false' test`：通过。
- `agent-console` 的 `npm test -- --run`、`npm run build`：通过。
- Docker 镜像、容器、数据卷与构建缓存均已清空；未保留 Docker 本地运行状态。
- `.idea` 和本地 `.env` 未纳入提交。

## 当前交付范围

- 不要求 Docker、Docker Compose、Nginx、TLS 证书、公网访问、上线或现场演示。
- 真实 DashScope/AgentScope 验收及数据库重置仅在未来另行批准、并提供独立非生产 MySQL 与凭据时执行。

## 下一步唯一动作

无。当前本地交付已经完成；后续如需真实模型验收或 V2.1 MCP，必须作为新任务明确授权。

## 优先文件

| 目的 | 文件 |
|---|---|
| 本地验证 | `scripts/`、两个 Workflow 测试、`SaveSessionPreferenceToolTest.java` |
| React 控制台 | `agent-console/`、`docs/runbook.md` |
| 可选未来扩展 | `docker-compose.yml`、`deployment/`、`scripts/live_acceptance.py` |

## 恢复原则

如开启新任务，先检查 `git status --short` 和相关 Diff；不回滚用户保留的 `.idea` 本地改动，也不下载 Docker 镜像或引入上线、现场演示与 V2.1 MCP 范围，除非用户明确授权。
