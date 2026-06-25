# Spring AI 2 First 升级总控

## 当前进度

| 阶段 | 状态 | 已落地 | 未完成验收 |
|---|---|---|---|
| Phase 0 基线与测试隔离 | 已完成 | Java 17 Enforcer、Jupiter、默认单测隔离、`*IT` + integration Profile | 无 |
| Phase 1 Spring AI 2 API 验证 | 已完成 | `ChatClient`、Tool Calling、Advisor、社区 `spring-ai-session` API 可用性测试 | 无 |
| Phase 2 Advisor 基础设施 | 已完成 | `ContextBudgetAdvisor`、`SessionMemoryAdvisor`、`EvidenceRetrievalAdvisor`、`ObservationTraceAdvisor` | 无 |
| Phase 3 Tool/MCP 收敛 | 已完成 | 自定义关键词路由退出主链路，Spring AI Tool Calling 承担工具调用循环，保留 Guard 安全包装 | 无 |
| Phase 4 主链路切换 | 已完成 | `AgentDispatchService -> SpringAiAgentRuntime -> ChatClient + Advisor Chain` | 无 |
| Phase 5 收口清理 | 已完成 | 删除旧 Harness 主链路、旧模型端口、旧路由 VO 和旧测试，README 已同步 | live evaluation 与消融结论 |
| 后续评测验收 | 待执行 | 保留 quick/full evaluation 设计 | 三组 live evaluation 和消融结论 |

## 技术评估

- 保留 Spring AI 2.0、Spring Boot 4.1、PGVector 和 MCP，主链路收敛到 Spring AI `ChatClient + Advisor Chain`。
- 不引入 OpenHarness、LangGraph、Spring AI Alibaba Graph、精确 tokenizer、reranker、多 Agent 或长期向量记忆。
- 旧 Harness 的动作循环已退出主链路，只保留证据治理、引用校验、拒答和运行态复盘等可迁移能力。
- 对外使用“受控 Agentic RAG 证据闭环”，不把 Agentic RAG 3.0 描述成行业标准。

## 当前执行模型

```text
AiAgentController
-> AgentDispatchService
-> SpringAiAgentRuntime
-> ChatClient
-> ContextBudgetAdvisor
-> SessionMemoryAdvisor
-> EvidenceRetrievalAdvisor
-> Spring AI Tool Calling
-> ObservationTraceAdvisor
-> summary / complete
```

模型调用由 Spring AI `ChatClient` 统一承接，项目不再维护 Harness Action Loop。

## 唯一入口与已删除冗余

- 执行主入口：`SpringAiAgentRuntime`
- 模型调用网关：`SpringAiChatClientPort`
- 本地 evidence 注入：`EvidenceRetrievalAdvisor`
- 本地检索端口：`ILocalEvidenceRetrievalPort`，当前实现 `AdaptiveLocalEvidenceRetrievalPort`
- 运行态观测：`ObservationTraceAdvisor`

已删除：

- 旧 Harness 主链路和旧 Action Loop 相关类
- `IAgentModelPort` 与旧 `AgentModelPort`
- `ToolRoutingDecisionVO`、`AgentExecutionContextVO`、`PlanStepTypeEnumVO`
- `AgenticRagRuntime`
- `HybridRagRetrievalPort` 和 `IRagRetrievalPort`
- `AgentPlanVO`、Plan 校验 VO 和 Aggregate plan 字段
- 旧 step-output 压缩服务与累计多次调用的上下文预算
- Run 表中失去语义的 context chars/summary 字段
- 旧 RAG Runtime 测试和默认 JUnit 4/Vintage

## 自适应检索边界

- `PROJECT_KNOWLEDGE` 默认 PGVector。
- 类名、方法名、配置键、异常文本或向量无结果时启用 BM25。
- 两个本地通道都有结果时才执行 RRF。
- evidence trace 标记上下文不足时才允许父块扩展。
- `OFFICIAL_DOCS / WEB_RESEARCH` 由 Spring AI Tool Calling 调用已注册的只读资料类 MCP。
- 外部 evidence 必须来自真实 ToolCallback 结果；无 URI 文本是低可信补充。

BM25/RRF、Small-to-Big 和二次检索是否保留，由 [评测门槛](evaluation/rag-evaluation-v1.md) 决定，不由架构偏好决定。

## Session 记忆边界

- 消息表只保存原文。
- Session 表只保存结构化摘要、游标、版本和过期状态。
- 只加载成功完整 Turn；孤立 USER 不注入。
- 摘要不保存外部事实和工具输出。
- 30 天 TTL，每日清理；乐观锁冲突重试一次。
- 清除 API 仅在 dev Profile 注册。

## 验收命令

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"

mvn -q "-DskipTests=false" test
mvn -q -Pintegration "-DskipTests=false" verify
mvn -q "-DskipTests" package
git diff --check
.\scripts\dev\run-local-smoke.ps1
```

默认 `mvn test` 不得访问 Docker、API key、网络或启动 MCP 子进程。live evaluation 分为 quick、custom、full 三层：日常修改跑 quick 抽样回归，能力定向修改跑 custom，阶段验收和效果结论才跑 full 全量评测。只有 full 报告可以用于更新 README/简历中的效果表述。

## 2026-06-23 验收记录

- 默认测试：41 个测试套件，187 个测试，0 failures，0 errors，13 skipped。
- integration Profile：17 个测试套件，108 个测试，0 failures，0 errors，12 skipped。
- Docker 下 MySQL、PGVector、Elasticsearch、Context7 Stdio MCP 和 Exa Streamable HTTP MCP 均完成启动与集成回归。
- live 已验证普通 Harness、项目知识检索、引用回答、当前架构知识样本和 2 个只读 MCP ToolCallback 注入。
- 外部 evidence 调用已处理百炼“思考模式不支持 required tool choice”的兼容边界；关闭该次调用的思考模式后，最终受账户 `free quota exhausted` 阻塞。
- 未运行三组 live evaluation，不产生或宣称任何效果提升数字。
