# AI Agent Station 执行计划与验收矩阵

## 当前能力闭环

| 能力 | 已实现 | 自动验证 | 真实验证 |
|---|---|---|---|
| Session FIFO、取消、SSE | 同一 `userId + sessionId` 串行，排队与执行请求均可取消 | Core 队列测试 | `live_acceptance` 待非生产环境执行 |
| Order Workflow V2 | `QUERY`、`TRACK`、`DIAGNOSE`，近期订单选择、问题补充、实时归属与物流诊断 | `OrderInquiryWorkflowTest`、评测场景 | QuestionCard 与重启恢复待执行 |
| After-sales Workflow V2 | 订单/原因/说明/确认卡，自动退款、人工审核、拒绝与状态查询 | `AfterSalesRefundWorkflowTest`、评测场景 | 双分支与状态查询待执行 |
| 售后事实 | `DEMO_AFTER_SALES_CASE` 与 `DEMO_REFUND_COMMAND` 分离，订单申请唯一、退款命令按运行幂等 | Local Gateway 测试 | 历史升级与真实 MySQL 待执行 |
| Router Policy | Core 规则优先；classpath Policy 仅声明 executor/domain/operation 白名单与冲突优先级 | 路由业务语料、Qwen Mock HTTP 与资源契约测试 | 仅规则未覆盖请求的真实模型兜底待批准 |
| ReAct 工具体系 | 五个只读 `ALLOW` 工具；会话偏好写入固定为 `ASK`；百炼原生工具禁用 | AgentScope 工具测试 | 五读一写与 ASK 待执行 |
| ReAct AgentSkill | 唯一、只读的 `agent-station-business-orchestration` 指导 Tool 选择和无代码只读编排；Tool 不分组隐藏 | Skill Repository 与 ReAct 装配测试 | `--skill-stability-runs 5` 需独立非生产环境和另行授权 |
| HITL | ASK 走同一 SSE 的 `intervention` 与旁路确认；ReAct 无恢复，Workflow 持久化恢复 | 确认协议与状态清理测试 | 真实偏好确认/拒绝/超时/取消待执行 |
| 会话记忆 | 生成/使用分离、tombstone、人工优先、版本契约、提示注入边界 | `AgentMemoryServiceTest`、Controller 测试 | 写入后下一轮偏好生效待执行 |
| 控制台/评测 | React SSE 控制台、业务卡片、记忆管理；规则 HTTP 回归 Markdown 报告 | Vitest、`scripts.evaluation` | 演示串联待执行 |

## 关键边界

- 退款、支付、发货、删除和账户变更不进入 ReAct；退款仅在最终 Workflow QuestionCard 确认后执行。
- `save_session_preference` 是可逆的人工会话记忆写入，拒绝、超时与取消均不执行；其结果可经记忆 API 编辑或软删除。
- ReAct 使用 `InMemoryAgentStateStore`，不支持跨请求/跨重启恢复；QuestionCard 以 MySQL `WORKFLOW_RUN` 保存，必须可恢复。
- 百炼模型原生工具（含原生联网搜索）保持关闭。未来需联网或接入第三方能力时，只通过框架 MCP 组件装配并独立验收。
- Router 不读取记忆。订单、物流、退款业务事实永远重新查询；Workflow 建议值必须经 answers API 显式提交。
- Router Policy 与 AgentSkill 职责分离：前者指导受控路由，后者指导 ReAct Tool 顺序、停止和降级；二者均不能替代 Core、Tool 权限或 Workflow。
- 本轮不引入向量库、跨会话记忆、Redis、分布式 ReAct 协调、Flyway 或 Testcontainers。

## 本地交付与验收顺序

1. 在备份后的非生产 MySQL 按运行手册中的升级顺序执行历史脚本和记忆版本脚本，验证版本冲突、恢复与退款幂等。
2. 先开启记忆生成并人工审阅，再独立开启 ReAct 使用，最后开启 Workflow 建议值；三个开关均可独立回退。
3. 本地验证可按近期订单选择并诊断物流 → 多阶段退款（自动/人工）→ ReAct 多工具分析 → ASK 保存回答偏好 → 重启后回答未完成 QuestionCard 的顺序执行。
4. 运行规则回归；仅在另行批准真实模型验收且准备独立非生产环境时，才运行 `live_acceptance`。需要稳定性数据时额外使用 `--skill-stability-runs 5` 并保存脱敏 Markdown 报告。

当前交付不包含上线和现场演示，因此 Docker、Docker Compose、Nginx、TLS 证书和公网访问均不属于必需环境。
