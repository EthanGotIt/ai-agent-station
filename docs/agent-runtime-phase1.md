# Agent Runtime Phase 1：Run / Step 执行模型

状态：已完成。

## 技术评估

本阶段不引入 OpenAI Agents SDK、LangGraph、CrewAI 等新框架。

原因：

- Phase 1 目标是统一现有 `Run -> Plan -> Step -> Result` 运行态，不涉及新的 Agent 循环、handoff 或复杂工具治理。
- 当前代码已经有 `AgentRunAggregate`、`AgentExecutionContextVO`、`ai_agent_run`、`ai_agent_step_run`，在现有结构上补生命周期视图成本最低。
- 引入新框架会把重点从 Spring AI 组件装配与 Flow Plan 执行转移到框架适配，当前收益不足。

后续 Phase 2/3 在执行前需要重新评估：如果工具治理、trace、resume 自研成本明显高于接入收益，再考虑小范围引入框架或 SDK。

## 本阶段交付

- 新增 `AgentRunLifecycleVO`，从 run 状态、step 状态和上下文摘要派生运行生命周期。
- `GET /api/v1/agent/run/{runId}` 返回 `lifecycle`，包含当前阶段、当前步骤、终止原因、步骤计数和上下文压缩标记。
- 计划校验失败时，run 会从 `RUNNING` 落到 `FAILED`，不再停留在运行中。
- 上下文预算达到终止阈值或用户取消时，未执行计划步骤会写入 `SKIPPED` 或 `CANCELLED`，并保留原因。
- 默认对话模型配置更新为 `qwen3.7-max`，继续走 DashScope OpenAI compatible 模式。

## 验收测试

已覆盖：

- 计划生成/解析与校验失败
- 步骤执行失败的终止原因派生
- 取消任务的终止原因派生
- 上下文压缩触发与 lifecycle 标记
- run 聚合根成功、失败、取消状态转换

推荐命令：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=AgentRunAggregateTest,AgentRunLifecycleVOTest,FlowPlanSupportTest,AgentContextWindowServiceTest" test
```

已执行验收：

- 目标单测：18 个通过，0 failures，0 errors。
- 跳过测试打包：`mvn -q "-DskipTests" package` 通过。
- diff 检查：`git diff --check` 通过。

未完成验收：

- 未跑真实 DashScope/API smoke。
- `qwen3.7-max` 当前是配置层切换，供应商侧模型名可用性需要接入真实 key 后验证。

## 对 Phase 2 的影响

- Tool Guard 会复用 Phase 1 的 `lifecycle`、`terminalReason` 和 step 状态，不重新设计 Run/Step 模型。
- Phase 2 的工具治理已调整为路由筛选、注入前过滤和调用期兜底，不再把工具授权校验放在 `flow_plan_validate` 阶段处理。
- Phase 2 只扩展工具治理，不引入完整多 Agent 通信、长期记忆或真实沙箱。

## 答辩材料

一句话亮点：

> 将原本的模型调用链路升级为 `Run -> Plan -> Step -> Result` 的轻量 Agent Runtime，支持计划校验、步骤追踪、失败/取消/跳过原因回溯和上下文压缩标记，使 Agent 执行过程可追踪、可终止、可复盘。

高频追问：

- 为什么这一步不引入 Agent 框架？
  因为当前阶段只解决运行态一致性，现有 Spring AI + Flow Plan 已经足够承载；引入框架会增加适配成本。
- 计划校验失败怎么处理？
  校验节点会标记 `flow_plan_validate` 为 `FAILED`，同步将 run 标记为 `FAILED`，并通过 `terminalReason` 暴露失败原因。
- 上下文过长怎么办？
  先由 `context_guard` 压缩历史输出；达到停止阈值后，不再发起新的 LLM 调用，未执行计划步骤标记为 `SKIPPED`。
- 用户取消怎么办？
  取消请求写入运行态，执行节点检查到取消后将 run 标记为 `CANCELLED`，未执行步骤标记为 `CANCELLED`。
