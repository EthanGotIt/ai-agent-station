status: active
updated: 2026-09-04

# Task Handoff

Goal:

- 完成 Commerce Guardian Agent 第一阶段“运行闭环加固”，让模型、工具、持久化事实和 SSE 在同一 Turn 内遵守明确的终止、预算和故障边界。
- 第一阶段验收稳定后，再以独立实施单元推进 2A Harness 式上下文压缩和 2B LangGraph 催发货编排试点。

Completed:

- Core 新增稳定停止原因、输出额度预留/结算、上下文预算检查和相同工具失败熔断；默认累计输出额度为 8,192 token，重复失败阈值为 3。
- Infrastructure 显式装配唯一 `ControlledToolCallingAdvisor` 与顺序执行的 `ControlledToolCallingManager`。FINISH、QuestionCard 或 Workflow 事实成功落库后截断同批剩余工具和额外模型请求；资源停止使用 `STOP_LIMIT`，重复失败使用 `FALLBACK` 并失败收口。
- 每次真实模型请求前按完整 Prompt 估算上下文并预留输出，响应后只结算一次；缺失/零 usage 和断流保守保留预留。正常与错误工具结果统一限制为有效 JSON，并保留标识和截断说明。
- 持久化 Item 成功后，SSE 事件发布失败只记录观测并依赖游标回放，不改写已经提交的 Turn 或业务事实。
- 生产 ContextAssembler 已切换到最新 300 条原始 Item；旧快照继续保留在库中，但第一阶段不再用快照跳过原始历史或提前触发摘要，并记录被裁剪 Item 数量。
- 运行参数、前端停止原因投影、架构文档和运行手册已同步；HTTP 请求格式与既有 Workflow、问答、审批协议保持兼容。
- 新增/调整 Core、Infrastructure、App 与前端测试覆盖同批截断、预算预留和幂等结算、缺失 usage、结果截断、连续失败重置、原始历史读取和前端具体停止原因。

Decisions:

- 本轮只实现第一阶段；DeepSeek Harness 压缩细节和 LangGraph 催发货节点记录不提前混入运行时。2A/2B 需在本阶段验收后分别设计、实现和验证。
- 保留 Spring AI 2.0.0、同 Thread FIFO、现有恢复路线和 Workflow 事实归属；不增加正常工具调用总次数上限或语义“无进展”判断器。
- 不持久化或展示原始 Thinking；本轮已获得用户授权，提交与推送只包含本阶段明确文件，不纳入 `.impeccable/critique/`。

TODO:

- 在当前第一阶段门禁基础上，先按独立计划实施 2A Harness 式上下文压缩；完成其压缩、连续水位、取消和重启恢复验收后，再实施 2B LangGraph 催发货试点。

Blocked:

- 当前无代码或测试阻塞。真实模型、浏览器和外部服务黄金路径属于后续现场验收，不改变本轮代码结论。

Next action:

- 恢复任务时先核对 `git status --short` 与本 handoff；若继续本计划，从 2A 的设计边界和快照水位接口开始，不回退第一阶段运行时改动。

Validation:

- `mvn clean '-DskipTests=false' test` 通过：Core 60、Infrastructure 90、App 20，Reactor `BUILD SUCCESS`。
- `npm --prefix agent-fronted run typecheck`、`npm --prefix agent-fronted test`（56 tests）和 `npm --prefix agent-fronted run build` 通过。
- Python `scripts.convention_check` 和 `unittest discover -s scripts/tests -p "test_*.py"` 通过（19 tests）；`git diff --check` 已通过。

Preserve:

- 保留用户已有的 `.impeccable/critique/` 资产及其他未纳入本阶段的工作区改动，不删除、不暂存、不提交。
- 保留数据库中的原始业务事实与旧快照；不保存 Prompt、Thinking、API key、完整敏感响应或真实模型原文。
- `AGENTS.md` 是完整长期规范；本 handoff 只保留当前恢复所需的最小事实。
