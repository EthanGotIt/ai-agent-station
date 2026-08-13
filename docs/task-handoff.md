# 任务交接

## 状态

`completed`

更新时间：2026-08-12

## 当前基线

- V2 三模块重构已固化为提交 `8b02be3`（`feat: establish v2 agent station baseline`）；旧 API、domain、trigger、types 模块和旧售后状态机已移除，依赖方向为 `app → core`、`app → infrastructure`、`infrastructure → core`。
- Order Workflow V2 支持 `QUERY`、`TRACK`、`DIAGNOSE`，缺订单号时生成近期订单 QuestionCard，诊断缺问题类型时继续生成卡。恢复从实时订单校验节点重入，不持久化订单快照。
- After-sales Workflow V2 支持申请与状态查询：依次收集订单、原因、说明和最终确认。自动退款创建 `DEMO_AFTER_SALES_CASE + DEMO_REFUND_COMMAND`，人工审核只创建 `PENDING_REVIEW` 申请单；同用户订单申请唯一。
- ReAct 的生产目录为五读一写：近期订单、订单快照、物流、售后状态、售后规则均为 `ALLOW`，`save_session_preference` 固定 `ASK`，仅写入当前会话的受控偏好。`acceptance` 探针仅在 `acceptance` Profile 注册。
- ReAct 为单回合，`InMemoryAgentStateStore` 在结束、超时、取消、异常时清理；Workflow 以 `WORKFLOW_RUN` 持久化恢复。SSE `workflow_question` 将 QuestionCard 与运行 ID/版本作为原子事件负载发送。
- 记忆 V2 保持生成/使用分离、后台提取、人工优先与 tombstone；偏好实际注入 ReAct 的语言、格式、详略指令。`manual-upgrade-domain-v2.sql` 负责将已有库补齐商品、物流、售后申请单和退款 `CASE_ID`。
- 收口测试已补齐订单五类诊断及边界时间、持久化 QuestionCard 重建恢复，退款的多阶段收集、重复申请、资格拒绝与陈旧确认；`save_session_preference` 覆盖越权值和缺失运行时归属。React 业务卡按后端真实字段渲染金额、商品、预计送达与建议，并验证 SSE CRLF 尾部缓冲。
- Router Policy、ReAct AgentSkill 与会话偏好编排已完成稳定性收口；候选代码基线为 `89ebda1`。控制台按协议区分文本与结构化 SSE，并正确处理 204 空成功响应。
- V2 已完成独立非生产 MySQL、真实 DashScope 与控制台人工串联验收；不要求上线或现场演示。仓库保留的 Docker 文件不属于当前验收，也不要求下载镜像、配置 TLS 或启动 Compose。
- V2.1 已完成退款业务生命周期代码闭环：操作员审核批准/驳回、审核与重试幂等键、申请单与退款命令乐观锁、本地异步 Worker 租约、有限自动重试、最终失败的人工重试，以及控制台审核队列均已落地。现有库需在备份后的非生产环境执行 `manual-upgrade-refund-lifecycle-v21.sql`。
- V2.1 HTTP 退款渠道边界已完成：`local` 为默认实现，`http` 使用 `refundId` 作为外部幂等键，只发送退款 ID、订单 ID、金额和币种，并统一超时与稳定失败码。人工审核申请会保留可用的实付金额；金额缺失时批准以业务冲突闭合失败。
- 独立 `scripts.refund_acceptance` 已完成本机 MySQL 六场景验收，覆盖审核/重试幂等、有限自动重试、最终失败、人工再试和渠道生效后的进程重启租约恢复；外部模型调用为 0。

## 最近验证

- `python -m scripts.convention_check`：通过。
- `python -m unittest discover -s scripts/tests -p "test_*.py"`：25 项通过。
- `python -m scripts.plan_audit --strict`：23/23 通过。
- `mvn clean '-DskipTests=false' test`：132 项通过（core 64、infrastructure 39、app 29）。
- `agent-console` 的 `npm test -- --run`：11/11 通过；`npm run build` 通过。
- 本阶段按约定未再使用 Impeccable 改版；控制台只做既定退款审核闭环与回归验证。
- `python -m scripts.live_acceptance --skill-stability-runs 5`：45/45 通过，五类稳定性场景均为 5/5，数据库重置完成。
- 控制台人工冒烟：SSE 时间线、业务卡片、QuestionCard 回答、连续 ASK 与记忆创建/读取/删除通过。
- `python -m scripts.refund_acceptance --reset-database --confirm-drop DROP_LOCAL_REFUND_ACCEPTANCE_SCHEMA`：六项通过，数据库重置完成，外部模型调用 0；脱敏结论见 `docs/acceptance/v21-refund-20260812.md`。
- 临时后端和 Vite 进程已停止，未保留 8090/5173 监听；`.idea` 和本地 `.env` 未纳入提交。
- `.idea` 和本地 `.env` 未纳入提交。

## 当前交付范围

- 不要求 Docker、Docker Compose、Nginx、TLS 证书、公网访问、上线或现场演示。
- V2 真实 DashScope/AgentScope 验收已完成；再次执行仍需明确授权和独立非生产环境。
- V2.1 不对接真实支付商；HTTP 边界和本机模拟渠道验收用于证明事务、幂等、超时、重试与重启恢复，不代表生产支付验收。

## 下一步唯一动作

审核本次完整 Diff，在排除用户 `.idea` 本地改动后形成单一 V2.1 候选提交；提交动作仍需用户明确要求。

## 优先文件

| 目的 | 文件 |
|---|---|
| 本地验证 | `scripts/`、两个 Workflow 测试、`SaveSessionPreferenceToolTest.java` |
| V2.1 退款验收 | `scripts/refund_acceptance/`、`HttpRefundExecutor.java`、`docs/acceptance/v21-refund-20260812.md` |
| React 控制台 | `agent-console/`、`docs/runbook.md` |
| 可选未来扩展 | `docker-compose.yml`、`deployment/`、`scripts/live_acceptance.py` |

## 恢复原则

如开启新任务，先检查 `git status --short` 和相关 Diff；不回滚用户保留的 `.idea` 本地改动，也不下载 Docker 镜像或引入上线、现场演示与 V2.1 MCP 范围，除非用户明确授权。
