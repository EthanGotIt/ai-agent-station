---
status: active
updated: 2026-09-29
---

# Task Handoff

## Goal

执行 [Commerce Guardian Agent 后续调整计划](../docs/plans/p8-turn-recovery-workflow-task.md)：保留 Thread → Turn → Item，将 WorkflowRun 仅更名为 WorkflowTask，并让新语义 Turn 可持久化暂停、接收分类型输入、等待 Command 后恢复原 Agent 循环。历史 P0—P7 的环境验收缺口继续单独追踪，不将旧证据算作新语义验收。

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
- P5：新增 `scripts/maintenance/workflow-inventory.sql` 只读盘点旧 Run、开放交互、未结算命令、待消费 continuation 和历史图快照；新增 `RetiredAgentWorkflowEngine`，旧持久化编排以 `WORKFLOW_COMPATIBILITY_REQUIRED` 受控拒绝；新 Run 仅路由 Java Workflow，Worker 结果投影不创建 continuation。
- P5：移除 Maven LangGraph4j 依赖、图引擎/节点、技术快照 Entity/Mapper 和生产 continuation gateway；保留 V8/V12 迁移、历史快照表、Turn/Item 兼容字段和编排标识以支持读取、排空与回滚。架构、运行手册、升级计划和实施追踪已同步。
- P5 验证：提交 `f8a2f9d` 已推送；干净快照的规范检查、Python 20 项、Maven Core 98/98、Infrastructure 109/109、App 22/22、HTTP 集成 9/9、MySQL acceptance 13/13 和 `context-acceptance,workflow-acceptance verify` 均通过。干净快照未安装前端依赖，前端门禁沿用 P4 证据；本阶段未改前端。当前环境没有目标 MySQL 只读凭据，尚未宣称线上旧 Run/continuation 已归零。
- P6 工具：提交 `b14a87e`、`f3a5316` 已推送；Live driver 保留上下文机制，增加 6 类长对话同 Thread 场景、普通后续 Turn、确定性 Workflow 结果核验和脱敏上下文指标；事实变化通过夹具公开幂等动作注入；模型准入门槛为基线 36 条、长对话 18 条。
- P6 离线验证：Python unittest 31 项、规范检查和 `git diff --check` 通过；确定性替身仍为 36/36 安全、36/36 路由。真实模型未运行，不把离线结果记作 Live 通过。
- P7 隔离运行：最终 Jar 在一次性 MySQL schema `CGA_P7_DD3737D06C15407B`（Flyway v14）和独立订单夹具上启动；曾发现并修复 `@Repository` 的 final 实现阻塞 Spring 代理。应用健康检查、订单夹具健康检查和浏览器默认桌面连接均通过。
- P7 验证：HTTP acceptance（Thread/Item 恢复、交互唯一性、Turn 幂等、物流、退款、催发货 3 次临时失败后重试、删除幂等）通过；干净快照 Maven `clean test` 为 Core 98、Infrastructure 109、App 22，profiles 为 HTTP 9、MySQL 13；前端 typecheck、Vitest 77、build 通过。证据详见 [P7 执行卡](../docs/plans/p7-release-acceptance.md)。
- 新计划文档：同 Turn 恢复、WorkflowTask 命名和 Queue/Steer 的目标边界已记录；产品、README、架构和总体路线已标明当前行为与目标行为的差异。
- 当前工作区基线重跑：`convention_check`、Python 31 项、前端 typecheck、Vitest 77 项、build 与 `git diff --check` 通过。`mvn clean test` 被工作区中预存的未跟踪 `infrastructure/.../workflow/langgraph` 源码阻断：这些源码仍引用已在 P5 移除的 LangGraph4j 类型；文件保持原样，不计为本计划新增代码。此前 P5/P7 干净快照 Maven 通过证据仍有效，但不替代当前工作区基线。
- P8 阶段 1：计划校准已以 `6e81420` 单独提交并推送；总体路线、执行卡、产品/架构入口已同步，历史 P5—P7 环境验收缺口仍未关闭。
- P8 阶段 2：WorkflowTask 内部模型、Store、Entity、Mapper、owner-recovery 类型及 Turn 关联更名已完成；`AGENT_WORKFLOW_RUN.RUN_ID`、`AGENT_TURN.WORKFLOW_RUN_ID` 和 HTTP `runId`/`workflowRunId` 保持显式兼容映射，无数据库迁移。Core 102、Infrastructure 131、App 25 单测及隔离 MySQL Items/Workflow acceptance 13 项均通过；为验证暂时移出的未跟踪 LangGraph4j 源目录已原样恢复。
- P8 Turn 恢复存储底座：Turn 新增执行语义版本（历史默认 0），V15 增量创建 `AGENT_TURN_EXECUTION_STATE`，记录累计主动时长、工具批次游标和受控调用快照；新 Turn 仍未启用语义版本 1。Core 102、Infrastructure 134、App 25 单测通过；隔离 MySQL 从 V9→V15 的 Items/Workflow acceptance 14 项通过，含恢复快照往返和 CAS。详细边界见 P8 执行卡和实施追踪。

## Decisions

- Spring AI 负责理解、只读查询和提交事项；Java Workflow 负责业务核验、授权、命令和确定性结果收尾。
- `WorkflowRun → WorkflowTask` 只改内部命名，保持职责、数据身份、既有表列及外部 `runId`/`workflowRunId` 兼容映射。
- 新语义 Turn 可在补参、批准或 Command 等待时持久化暂停；答复和动作结果恢复同一 `turnId`，不创建回答/决策子 Turn 或自动 continuation Turn。
- 普通消息默认 Queue 为后续 Turn；显式 Steer 追加当前未结束 Turn。QuestionCard 答案、Checkpoint 决策、Steer 与 Worker 结果保持各自输入协议和校验。
- 暂停期间释放模型请求、执行线程和数据库事务；恢复不得重置主动执行预算或重复已完成工具/外部命令。
- 已提交 Command 的取消不撤销副作用；晚到结果照常持久化，但不能重新激活已取消 Turn。
- 旧 Run 按原持久化版本兼容运行或经用户明确取消；未知外部副作用先核验，不能强行终结。
- 保留上下文存储、注入、预算和摘要机制；不将摘要作为授权或业务成功事实。
- 单 Turn 复合请求和 Queue／Steer 是本轮后续执行卡的一部分；依赖结果的步骤等待原 Command 结果，独立只读查询不等待写操作。
- 工作区有本任务开始前的未提交改动，逐阶段保留并核实，不整体还原；本次按用户明确要求提交并推送 P0—P3。

## TODO

- 用目标环境只读账号执行 `scripts/maintenance/workflow-inventory.sql`，保存 P5 排空报告并确认旧 Worker/服务/队列退出。
- 在提供两套既定模型凭据后，按每套 54 条记录运行 P6 Live，保存脱敏摘要并完成模型选择。
- 补做 P7 的兼容版本回滚、真实浏览器 SSE 断线、窄屏/移动浏览器专项，并把证据写回执行卡；601 条 Items/游标已有 MySQL acceptance 覆盖。
- 设计并实现新 Turn 恢复语义版本、工具调用/批次执行位置、恢复输入与持久化幂等信号；同时保留既有 Turn 恢复行为版本。
- P5 目标数据库只读盘点、P6 两套模型各 54 条 Live 评测、P7 兼容版本回滚及真实浏览器 SSE/响应式检查仍是未完成验收；实施 P8 时保留这些独立缺口并按可用隔离环境补证。

## Blocked

P2 已验证完成。订单服务未提供独立的按幂等键查询 API；本地夹具已证明同键重放可恢复缓存回执且只产生一次业务变更。真实订单服务启用前仍需确认其服务端幂等契约；若不能确认，未知结果须停留在人工核验。

## Next action

继续 P8 Turn 恢复：新增持久化、可去重的恢复信号，并让 QuestionCard/Checkpoint admission 将已版本化的 owner Turn 从等待态恢复为同一 `turnId` 的排队态；在接通路由前保留语义版本 0 准入。先定义 signal 与交互 CAS、Thread Item Sequence、重复请求回放的同一事务不变量，再覆盖并发输入和重启恢复。

## Validation

P0—P7 的历史代码与隔离验收见 [实施追踪](../docs/implementation-traceability.md) 与阶段执行卡。P8 当前仅有计划校准、WorkflowTask 命名和恢复存储底座证据；恢复信号、原 Turn 补参/决策、Tool 批次/预算续接、Worker 结果恢复、Queue/Steer 和复合请求未实现或验证。真实目标库 P5 盘点、真实模型、兼容版本回滚、真实浏览器 SSE 和响应式浏览器专项尚未执行；旧验收不能替代新语义证据。

## Preserve

- 保留数据库原始事实、历史 Run、Item、命令与旧快照；旧 Run 排空前保留兼容恢复能力。
- 不记录 Prompt、Thinking、密钥、完整敏感响应或真实模型原文。
- 提交、推送和部署依据当时明确授权，不从历史快照推断。
