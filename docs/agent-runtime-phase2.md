# Agent Runtime Phase 2：Tool Calling 治理

> 历史阶段记录：当前执行内核已在 Phase 8 替换为 Spring AI Alibaba ReactAgent GraphRuntime。

状态：核心代码已完成，目标单测已通过，真实 MCP smoke 待本地 key 与工具服务验证。

## 技术评估

本阶段不引入 OpenAI Agents SDK、LangGraph、CrewAI 等新框架。

原因：

- Phase 2 的目标是工具治理，不是替换 Agent 执行模型。
- 当前项目已经有 MCP 动态工具路由、Flow Plan 结构校验和 Spring AI `ToolCallback` 注入点。
- 风险分级、运行时工具筛选、禁用工具拦截、工具异常归一化可以在现有链路内完成，改动更小，也更贴合当前简历项目。

## 本阶段交付

- 新增 `ToolGuardPolicy`，按工具名做轻量风险分级：`LOW / MEDIUM / HIGH / DANGEROUS`。
- 路由阶段会从候选工具中剔除危险工具，并在 `tool_routing` payload 中输出 `blockedToolNames`、`blockedToolReasons`。
- Plan 校验阶段只校验结构、依赖、步数和步骤类型，不再提前绑定具体工具。
- 模型调用阶段只注入本轮 allowed set 内且未被 Tool Guard 拦截的工具。
- 新增 `GuardedToolCallback`，统一把工具参数错误和调用异常转成结构化错误 JSON，避免异常直接击穿执行链路。

## 验收测试

已覆盖：

- Plan 不再输出 `toolName` 字段，工具选择由执行阶段的路由和注入链路完成。
- 危险工具即使出现在模型输出中，也会在路由、注入或调用期被 Tool Guard 拒绝。
- 路由阶段会拦截危险工具，并保留同一 MCP 中的安全工具。
- 如果本轮只剩危险工具，工具路由会降级为不启用工具。
- 工具参数错误统一返回 `TOOL_ARGUMENT_INVALID`。
- 工具不在本轮授权集合统一返回 `TOOL_NOT_AUTHORIZED`。
- 危险工具回调层统一返回 `TOOL_FORBIDDEN`。

推荐命令：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=FlowToolCapabilityServiceTest,FlowPlanSupportTest,AgentModelPortTest,AgentRunLifecycleVOTest,AgentRunAggregateTest,AgentContextWindowServiceTest" test
```

已执行结果：目标单测 27 个通过，0 failures，0 errors。

## Smoke 示例

工具治理 smoke 重点看 `tool_routing` 事件：

- `allowedToolNames`：本轮真正允许注入给模型的工具。
- `blockedToolNames`：被 Tool Guard 拦截的危险工具。
- `blockedToolReasons`：危险工具被拒绝的原因。
- `selectedTools[].riskLevel`：选中 MCP 工具组的最高风险等级。

Plan 不承载具体工具名；实际 ToolCallback 只会注入本轮 allowed set 内且未被 Tool Guard 拦截的工具。

## 答辩材料

一句话亮点：

> 在 MCP 动态工具路由基础上增加 Tool Guard，形成“路由筛选、注入前过滤、调用期兜底”的治理链路，避免模型绕过本轮授权工具集合调用危险工具，并把工具失败统一转成可观测结果。

高频追问：

- 模型为什么不能随便调工具？
  工具可能访问外部系统或产生副作用，所以每轮只注入经过路由和风险校验的工具。
- 如何避免危险工具？
  路由阶段先按风险分级过滤，ToolCallback 注入前再过滤 allowed set 和危险工具，调用期由 `GuardedToolCallback` 兜底。
- 工具失败怎么办？
  `GuardedToolCallback` 捕获参数错误和调用异常，返回结构化错误 JSON，后续执行链路可以继续复盘失败原因。
