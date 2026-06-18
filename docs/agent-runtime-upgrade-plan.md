# AI Agent Station 升级总控方案

## 当前进度

| 阶段 | 状态 | 代码 | 测试 | 文档 | 答辩材料 |
| --- | --- | --- | --- | --- | --- |
| Phase 1-7：轻量受控 Runtime、Tool Guard、RAG 显式化、上下文治理、持久化 session 短期记忆 | 已完成 | 已完成 | 已通过历史验收 | 已完成 | 已沉淀 |
| 当前阶段：Controlled Agent Harness + Agentic RAG 3.0 | 已完成 | 主链路已切换 | 全量测试已通过 | README / smoke / defense 已更新 | 已更新 |

## 项目变化

项目主线已经从固定计划式执行收敛为 Controlled Agent Harness：

- `AgentDispatchService` 改为调用 `AgentHarnessExecuteService`。
- 单次请求生命周期改为 `Run -> HarnessContext -> Action -> Observation -> Evaluation -> Final`。
- 旧计划生成、计划校验、Step1-6 主执行链路和低价值旧测试已删除。
- `RuntimeToolCapabilityService` 只面向企业知识助手场景筛选 docs/search 类 MCP 工具。
- `AgenticRagRuntime` 成为 RAG 唯一主入口，旧隐式 RAG Advisor 已删除，避免同一检索能力存在两套入口。
- 默认 embedding 切换为阿里百炼 `text-embedding-v4`，维度保持 `1024`，不再依赖第三方向量模型默认配置。

当前项目仍不是完整多 Agent 通信框架、长期用户画像系统、危险工具沙箱或通用工作流引擎。

## 技术评估

本阶段不引入 OpenHarness、LangGraph、OpenAI Agents SDK 或 Spring AI Alibaba 图执行运行时。

原因：

- 当前代码已经有 Spring AI、MCP ToolCallback、MyBatis、运行态表、PGVector/ES 检索和本地 smoke 脚本，直接迁移框架会扩大改造面。
- OpenHarness 的价值主要是 harness 思想：受控动作、观测、评估、终止和可复盘 trace，本阶段已通过自研轻量服务吸收。
- Agentic RAG 的关键不是继续叠算法，而是检索规划、证据评估、有限二次检索、MCP 只读 evidence 融合和评测闭环。
- `text-embedding-v4` 通过 DashScope OpenAI compatible 模式接入，能复用现有 Spring AI OpenAI embedding 适配，避免新增额外向量模型依赖和 schema 变更。

## 代码健康规则

- 一个能力只保留一个主入口：Harness 执行只走 `AgentHarnessExecuteService`，RAG 只走 `AgenticRagRuntime`。
- 一个概念只保留一个命名：Action 决策使用 `AgentActionParser / AgentActionPolicy`，不再新增 Planner/IntentAgent 等相近类名。
- MCP 默认工具只保留 docs/search 场景，避免无关工具让项目看起来像技术堆砌。
- 不新增数据库表，不新增碎片化 SQL 文件，直接更新原 seed。
- 不提交真实 API key，配置和 SQL 只保留占位符。

## 代码冗余与后续清理

当前仍需跟踪的观察项：

- Harness 客户端配置已完成命名收口，后续不再使用旧流程配置叙事。
- 旧隐式 RAG Advisor 已删除，RAG 只通过 `AgenticRagRuntime` 主入口执行。
- `HybridRagRetrievalPort` 内部仍封装 PGVector、BM25、RRF、Small-to-Big，当前由 `AgenticRagRuntime` 控制何时检索和是否二次检索；后续如果要更细粒度评测，可拆成本地检索通道接口。
- `AgentHarnessExecuteService` 目前承担 run 初始化、工具路由、action loop 和流式输出，后续若继续扩展 resume/handoff，可再拆 action executor。
- 上下文预算仍是轻量估算，不是精确 tokenizer。

## 阶段验收记录

已通过目标单测：

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -pl ai-agent-station-app -am "-DskipTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=AgentHarnessSupportTest,AgenticRagRuntimeTest,RuntimeToolCapabilityServiceTest,AgentRunLifecycleVOTest" test
```

结果：15 个测试通过，0 failures，0 errors。

已通过回归验收：

- `mvn -q clean -pl ai-agent-station-app -am "-DskipTests=false" test`
  - 结果：173 个测试通过，0 failures，0 errors。
- `mvn -q "-DskipTests" package`
  - 结果：打包通过。
- `git diff --check`
  - 结果：通过，仅有 Windows 工作区换行转换提示。

待完成验收：

- 本地 live smoke：`.\scripts\dev\run-local-smoke.ps1`

## 后续方向

- 为 Agentic RAG 增加 20-30 条企业知识助手 QA case，覆盖单跳、多跳、无证据、歧义和 MCP 外部资料补充。
- 如果评测证明 `HybridRagRetrievalPort` 的固定混合策略限制效果，再拆分 PGVector、BM25、MCP evidence source 和后处理链。
- 如果 action loop 继续复杂化，再考虑引入更正式的 harness trace/evaluation 结构，而不是回到固定流程。
