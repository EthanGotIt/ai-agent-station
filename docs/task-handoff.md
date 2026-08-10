# 任务交接

## 状态

`active`

更新时间：2026-08-10

## 当前基线

- V2 三模块重构已固化为提交 `8b02be3`（`feat: establish v2 agent station baseline`）；旧 API、domain、trigger、types 模块和旧售后状态机已移除，依赖方向为 `app → core`、`app → infrastructure`、`infrastructure → core`。
- Order Workflow V2 支持 `QUERY`、`TRACK`、`DIAGNOSE`，缺订单号时生成近期订单 QuestionCard，诊断缺问题类型时继续生成卡。恢复从实时订单校验节点重入，不持久化订单快照。
- After-sales Workflow V2 支持申请与状态查询：依次收集订单、原因、说明和最终确认。自动退款创建 `DEMO_AFTER_SALES_CASE + DEMO_REFUND_COMMAND`，人工审核只创建 `PENDING_REVIEW` 申请单；同用户订单申请唯一。
- ReAct 的生产目录为五读一写：近期订单、订单快照、物流、售后状态、售后规则均为 `ALLOW`，`save_session_preference` 固定 `ASK`，仅写入当前会话的受控偏好。`acceptance` 探针仅在 `acceptance` Profile 注册。
- ReAct 为单回合，`InMemoryAgentStateStore` 在结束、超时、取消、异常时清理；Workflow 以 `WORKFLOW_RUN` 持久化恢复。SSE `workflow_question` 将 QuestionCard 与运行 ID/版本作为原子事件负载发送。
- 记忆 V2 保持生成/使用分离、后台提取、人工优先与 tombstone；偏好实际注入 ReAct 的语言、格式、详略指令。`manual-upgrade-domain-v2.sql` 负责将已有库补齐商品、物流、售后申请单和退款 `CASE_ID`。
- 收口测试已补齐订单五类诊断及边界时间、持久化 QuestionCard 重建恢复，退款的多阶段收集、重复申请、资格拒绝与陈旧确认；`save_session_preference` 覆盖越权值和缺失运行时归属。React 业务卡按后端真实字段渲染金额、商品、预计送达与建议，并验证 SSE CRLF 尾部缓冲。
- 单机演示资产已加入：根目录 `docker-compose.yml`、多阶段 Java/React 镜像、Nginx TLS/Basic Auth/限流模板，以及 MySQL 备份恢复脚本。`.dockerignore` 将构建上下文限制为约 686KB，密钥、证书与备份均被 Git 忽略。

## 最近验证

- `D:\Application\miniconda3\python.exe -m scripts.convention_check`：通过。
- `D:\Application\miniconda3\python.exe -m unittest discover -s scripts/tests -p "test_*.py"`：13 项通过。
- `D:\Application\miniconda3\python.exe -m scripts.plan_audit --strict`：23/23 通过。
- `mvn clean '-DskipTests=false' test`：通过。
- `agent-console` 的 `npm test -- --run`、`npm run build`：通过。
- `docker compose config --quiet`：使用一次性测试变量通过；未启动容器、创建数据卷或写入业务数据。
- Docker Desktop 可用，但首次应用镜像构建因 Docker Hub 基础层下载吞吐极慢而主动停止，尚未产生应用镜像；这不构成 Dockerfile 构建成功的证据。
- `.idea` 和本地 `.env` 未纳入提交。

## 外部验收前置条件

- 当前会话环境未提供有效 `DASHSCOPE_API_KEY`、`MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD` 或受信任 TLS 证书，也没有可确认的已备份非生产 MySQL。因此不得执行升级脚本、`docker compose up` 或 `scripts.live_acceptance --reset-database`。
- 真实验收应在具备上述前置条件后，按 `runbook.md` 的历史升级顺序完成备份库升级，再运行 `python -m scripts.live_acceptance --reset-database --confirm-drop DROP_LOCAL_AI_AGENT_STATION_SCHEMAS`；Docker 镜像构建需先等待 Docker Hub 基础镜像下载完成。

## 下一步唯一动作

准备独立非生产 MySQL、DashScope 密钥和受信任 TLS 证书后，先完成 Docker 两个镜像构建并启动 Compose 演示栈，再按 `runbook.md` 执行 DDL/升级链与真实 DashScope/AgentScope 分场景验收；不得开始 V2.1 MCP 联网搜索。

## 优先文件

| 目的 | 文件 |
|---|---|
| 本地单机演示 | `docker-compose.yml`、`deployment/`、`docs/runbook.md` |
| 真实验收 | `scripts/live_acceptance.py`、`docs/runbook.md`、`docs/dev-ops/mysql/sql/` |
| Workflow 和 ASK 回归 | 两个 Workflow 测试、`SaveSessionPreferenceToolTest.java` |

## 恢复原则

先检查 `git status --short` 和相关 Diff；下一轮只修改当前 V2.0 验收范围，不回滚用户保留的 `.idea` 本地改动，也不将真实密钥、证书、备份或 V2.1 MCP 代码带入提交。
