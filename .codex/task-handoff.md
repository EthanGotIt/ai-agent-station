---
status: active
updated: 2026-09-28
---

# Task Handoff

## Goal

执行 [Commerce Guardian Agent 总体调整计划](../docs/upgrade-plan.md)，按 P0—P7 将售后写操作迁移到确定性 Java Workflow，退出 LangGraph4j 与自动 Agent continuation，并完成隔离环境交付验收。

## Completed

- P0：总体执行卡、文档导航和当前/目标架构状态已校准；基线通过，证据见实施追踪。
- P1：Worker 结果投影与 continuation admission 解耦。无 continuation gateway 时仍持久化 WorkflowResult、核验步骤、订单事实与 Turn 状态；只有实际新建 continuation 才记录 Agent handoff。策略由 Run 的不可变编排版本决定。
- P1 验证：Worker 定向 7 项通过；Core 97 项、Infrastructure 全量 140 项通过。
- P0 基线：Python 26 项、Maven 261 项、MySQL acceptance 9 项、前端 76 项，typecheck/build 通过。
- P2：区分明确失败与未知动作结果，持久化独立核验预算；HTTP 响应丢失使用原幂等键安全重放，未知结果耗尽后保留人工核验状态。数据库 V13、Items/上下文/前端投影及删除成功证据已贯通。
- P2 验证：Core 99、Infrastructure 145、HTTP 集成 9、App 单测 25 均通过；隔离 MySQL acceptance 10 项通过，覆盖 V9→V13 迁移、旧行保留、命令核验状态 round-trip 及独立核验预算。Python 27 项、前端 typecheck/Vitest 77/build、规范检查及 diff 检查通过。完整 reactor 首次执行发现的基线列归属、迁移夹具、MyBatis 自定义字段映射及版本断言均已修复，并重跑 MySQL acceptance 通过。
- P3：新增 `EXPEDITE_JAVA_V1` 和按持久化 Run 版本选择恢复引擎的路由。催发货 Java 状态机以 WorkflowRun/QuestionCard/Checkpoint/Command 恢复，不依赖图快照；订单资格与归属在批准前重核验，事务内 CAS 后创建唯一命令，Java Worker 结果确定性收尾。
- P3 验证：Java Workflow 5 项、版本路由 3 项测试通过；Core 100、Infrastructure 153、App 25 单测通过，HTTP 集成 9 项通过；隔离 MySQL Workflow acceptance 6 项通过，覆盖 Java 编排版本往返与并发 Checkpoint 批准。Python 27、前端 Vitest 77、typecheck/build、规范检查通过；`mvn -B -Pworkflow-acceptance '-DskipTests=false' verify` 成功。
- 历史 Items、上下文、LangGraph V1/V2、Worker 和 Live driver 证据保留为历史实现证据。
- P0—P3 已分别提交：P0 `5d3790f`、P1 `6ae842c`、P2 `f121afa`、P3 `39e908f`。P3 原先先行提交，随后补齐并验证 P0—P2；保留了此前未提交的 V2、Items 页面恢复及评测工作。
- P0—P3 阶段分支已通过 [PR #10](https://github.com/EthanGotIt/ai-agent-station/pull/10) 合并到主力分支，合并提交为 `0a5cb00`；后续阶段直接在 `codex/commerce-guardian-agent` 上提交。
- P4：退款与删除已接入 `REFUND_JAVA_V1`/`DELETE_JAVA_V1`，共享确定性授权、事实复核、CAS 命令创建和 Worker 结果投影；新增订单写预留防止同一用户跨 Thread 产生冲突写命令；后续 Turn 注入最新 Workflow/Command 事实。
- P4 验证：定向 18 项、Core 102 项、Infrastructure 164 项、App 25 项单测通过；HTTP 集成 9 项、隔离 MySQL acceptance 13 项通过；Maven `context-acceptance,workflow-acceptance verify` 成功。
- 当前提交快照完整验收：规范检查、Python 19 项、前端 typecheck/Vitest 76 项/build 通过；Maven reactor Core 96、Infrastructure 133、App 22 单测及 HTTP 9、MySQL 12 项集成测试通过。

## Decisions

- Spring AI 负责理解、只读查询和提交事项；Java Workflow 负责业务核验、授权、命令和确定性结果收尾。
- 新流程不自动创建 Agent continuation。后续用户消息作为普通 Turn，从持久化业务事实获取事项状态。
- 旧 Run 按原持久化版本兼容运行或经用户明确取消；未知外部副作用先核验，不能强行终结。
- 保留上下文存储、注入、预算和摘要机制；复合请求独立只读查询列作后续增强。
- 工作区有本任务开始前的未提交改动，逐阶段保留并核实，不整体还原；本次按用户明确要求提交并推送 P0—P3。

## TODO

- 执行 P5：盘点并排空旧 LangGraph Run 与 continuation，确认旧事项归零后移除 LangGraph4j 生产执行路径。
- P5 排空后移除 LangGraph4j；P6 完成真实模型长对话质量评测；P7 完成三类流程隔离端到端、浏览器及部署回滚演练。

## Blocked

P2 已验证完成。订单服务未提供独立的按幂等键查询 API；本地夹具已证明同键重放可恢复缓存回执且只产生一次业务变更。真实订单服务启用前仍需确认其服务端幂等契约；若不能确认，未知结果须停留在人工核验。

## Next action

盘点未完成的 Legacy/Graph Run、开放交互、未结算命令和 continuation，形成 P5 排空清单；在关闭旧准入前保留按持久化版本恢复的兼容路径。

## Validation

P0—P4 自动化和隔离验收通过项见 [实施追踪](../docs/implementation-traceability.md) 与阶段执行卡。真实订单平台、浏览器、真实模型和发布回滚验收尚未执行；这些仍分别由 P6/P7 覆盖。

## Preserve

- 保留数据库原始事实、历史 Run、Item、命令与旧快照；旧 Run 排空前保留兼容恢复能力。
- 不记录 Prompt、Thinking、密钥、完整敏感响应或真实模型原文。
- 提交、推送和部署依据当时明确授权，不从历史快照推断。
