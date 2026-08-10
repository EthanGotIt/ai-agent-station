# 任务交接

## 状态

`active`

更新时间：2026-08-10

## 当前基线

- Order Workflow V2 已支持 `QUERY`、`TRACK`、`DIAGNOSE`，缺订单号时生成近期订单 QuestionCard，诊断缺问题类型时继续生成卡。恢复从实时订单校验节点重入，不持久化订单快照。
- After-sales Workflow V2 已支持申请与状态查询：依次收集订单、原因、说明和最终确认。自动退款创建 `DEMO_AFTER_SALES_CASE + DEMO_REFUND_COMMAND`，人工审核只创建 `PENDING_REVIEW` 申请单；同用户订单申请唯一。
- ReAct 的生产目录为五读一写：近期订单、订单快照、物流、售后状态、售后规则均为 `ALLOW`，`save_session_preference` 固定 `ASK`，仅写入当前会话的受控偏好。`acceptance` 探针仍只在 `acceptance` Profile 注册。
- ReAct 为单回合，`InMemoryAgentStateStore` 在结束、超时、取消、异常时清理；Workflow 以 `WORKFLOW_RUN` 持久化恢复。SSE `workflow_question` 已将 QuestionCard 与运行 ID/版本作为一个原子事件负载发送。
- 记忆 V2 保持生成/使用分离、后台提取、人工优先与 tombstone；偏好实际注入 ReAct 的语言/格式/详略指令。`manual-upgrade-domain-v2.sql` 负责将已有库补齐商品、物流、售后申请单和退款 `CASE_ID`。

## 最近验证

- `mvn test`：通过（包含新增 Workflow V2 和 ASK 偏好工具测试）。
- 其余规范、Python 与 clean Maven 验证需要在本次变更结束时重新执行。

## 下一步唯一动作

在已备份的非生产 MySQL 按历史升级顺序执行脚本直至 `manual-upgrade-domain-v2.sql`，然后用真实 DashScope 凭据运行 `python -m scripts.live_acceptance --reset-database --confirm-drop DROP_LOCAL_AI_AGENT_STATION_SCHEMAS`，验收五读一写工具、QuestionCard 恢复、自动/人工售后和 ASK 确认/拒绝场景。

## 优先文件

| 目的 | 文件 |
|---|---|
| QuestionCard 与恢复 | `ai-agent-station-core/src/main/java/cn/ethan/core/workflow/model/WorkflowQuestionModel.java`、`AgentRuntimeService.java`、两个 Workflow |
| ReAct 工具确认 | `ai-agent-station-infrastructure/src/main/java/cn/ethan/infrastructure/agentscope/executor/AgentScopeReActExecutor.java`、`agentscope/tool`、`AgentController.java` |
| 会话记忆 | `AgentMemoryService.java`、`AgentMemoryExtractionCoordinator.java`、`AgentMemoryStore.java`、`MybatisAgentMemoryStore.java` |
| 数据库迁移 | `docs/dev-ops/mysql/sql/manual-upgrade-domain-v2.sql`、`ai-agent-station.sql` |

## 恢复原则

先检查 `git status --short` 和相关 Diff。当前工作区包含更大范围的用户重构改动；只在与上述文件重叠时审慎合并，不回滚无关修改。
