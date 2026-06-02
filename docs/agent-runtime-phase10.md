# Phase 10：MCP 可观测性、有限重试与运行时逻辑收敛

## 状态

已完成。

## 技术评估

- 不新增 LLM Tool Selector：当前工具规模经过规则路由后已经受控，优先优化现有路由的稳定性和可解释性。
- 不接精确 tokenizer：使用 Alibaba 官方 `TokenCounter.approximateMsgCounter(int)`，通过字符比配置校准近似预算。
- 不自研工具重试循环：使用 Alibaba 官方 `ToolRetryInterceptor`。
- 区分 MCP 初始化重试和工具执行重试：前者属于基础设施生命周期，后者属于 Agent 工具调用链。

## 代码变化

- 规则路由使用具名标签常量、最多三个 MCP Server 的具名上限、统一规范化和稳定同分排序。
- `selectedReason` 改为根据本轮实际命中标签生成。
- `SummarizationHook` 显式使用可校准的官方近似 token 估算器。
- MCP 生命周期管理器新增安全快照：只包含 ID、名称、状态、初始化次数、工具数、耗时和脱敏失败摘要。
- 新增 `/actuator/health/mcpClients`，部分 MCP 失败只标记 `DEGRADED`，不阻断应用 readiness。
- `graph_lifecycle` 增加工具解析耗时、注入数和本轮 MCP 状态摘要。
- `GuardedToolCallback` 只负责调用期授权和危险工具拒绝。
- `ToolRetryInterceptor` 只对低风险查询类 MCP 工具执行一次有限重试。
- `StructuredToolErrorInterceptor` 在重试耗尽后统一返回结构化错误。

## 执行链路

```text
MCP configuration register
  -> concurrent prewarm / lazy initialize
  -> lifecycle safe snapshot
  -> stable rule routing + Tool Guard
  -> filtered ToolCallback injection
  -> StructuredToolErrorInterceptor
       -> ToolRetryInterceptor(query-only, maxRetries=1)
       -> GuardedToolCallback authorization
       -> MCP invocation
```

## 验收命令

```powershell
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=RuntimeToolCapabilityServiceTest,McpClientLifecycleManagerTest,McpClientsHealthIndicatorTest,ToolRetryInterceptorCompatibilityTest,ReactAgentCompatibilityTest" test
mvn -q "-DskipTests=false" test
mvn -q "-DskipTests" package
git diff --check
.\scripts\dev\run-local-smoke.ps1
```

## 验收结果

- Phase 10 目标单测：`20` 个通过，`0` 失败。
- 完整 Maven 回归：`139` 个通过，`0` 失败。
- 跳过测试打包和 `git diff --check` 通过。
- live smoke 通过：`GraphRuntime / MCP health / 工具路由 / RAG 证据 / PostgreSQL session checkpoint` 均完成验收。
- MCP 预热后 `/actuator/health/mcpClients` 返回 `UP`：`5` 个 MCP Client 全部进入 `READY`，且未暴露 header、env、command 或 API key。

## 暂缓项

- LLM Tool Selector。
- 精确 Qwen tokenizer。
- Skills、HITL、Store 长期记忆和多 Agent。
