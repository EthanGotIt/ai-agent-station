# Evidence-Governed Harness 升级总控

## 当前进度

| 阶段 | 状态 | 已落地 | 未完成验收 |
|---|---|---|---|
| Phase 0 基线与测试隔离 | 已完成 | Java 17 Enforcer、Jupiter、默认单测隔离、`*IT` + integration Profile | 无 |
| Phase 1 Harness 协议 | 已完成 | `RETRIEVE / ASK_CLARIFY / FINALIZE`、Evidence Board、双层 Policy、2-4 轮软上限、live action trace | 无 |
| Phase 2 Evidence Retrieval | 代码与集成完成 | 唯一入口、ragId scope、按需 BM25/RRF/父块扩展、按来源 MCP、真实 ToolCallback 采集、引用校验 | 百炼额度恢复后补完整外部 evidence smoke |
| Phase 3 评测驱动精简 | 框架完成 | 60 条冻结数据、三种对照模式、指标计算和保留门槛 | 三组 live evaluation 和消融结论 |
| Phase 4 Session 记忆 | 已完成 | 完整 Turn、结构化摘要、最近四 Turn、偏好、乐观锁、TTL、dev 清除、MySQL integration | 无 |
| Phase 5 收口验收 | 代码验收完成 | 删除旧 Plan/RAG Runtime、错误累计预算和失效字段，README 已同步 | live evaluation 与消融结论 |

## 技术评估

- 保留 Spring AI 2.0、Spring Boot 4.1、PGVector、MCP 和自研轻量 Harness。
- 不引入 OpenHarness、LangGraph、Spring AI Alibaba Graph、精确 tokenizer、reranker、多 Agent 或长期向量记忆。
- 借鉴 Agent Harness 的“动作、观测、策略、终止”边界，不引入另一套执行框架。
- 对外使用“受控 Agentic RAG 证据闭环”，不把 Agentic RAG 3.0 描述成行业标准。

## 当前执行模型

```text
Run
-> Harness decision
-> RETRIEVE(sourceType, queries) | ASK_CLARIFY | FINALIZE
-> EvidenceRetrievalService
-> Evidence Board
-> deterministic Evidence Policy
-> Grounded answer with [E1] citations
```

模型只选择高层来源，不控制 PGVector、BM25、RRF、Small-to-Big 或具体 MCP 工具。最终回答不来自 Action JSON。

## 唯一入口与已删除冗余

- Harness 主入口：`AgentHarnessExecuteService`
- 动作副作用：`HarnessActionExecutor`
- 检索主入口：`EvidenceRetrievalService`
- 本地检索端口：`ILocalEvidenceRetrievalPort`，当前实现 `AdaptiveLocalEvidenceRetrievalPort`
- 最终回答：`GroundedAnswerService`

已删除：

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
- Evidence Board gap 为 `CONTEXT_INCOMPLETE` 时才允许父块扩展。
- `OFFICIAL_DOCS` 只路由 docs MCP，`WEB_RESEARCH` 只路由 search MCP。
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

默认 `mvn test` 不得访问 Docker、API key、网络或启动 MCP 子进程。live evaluation 使用百炼、PGVector、可选 ES 和 MCP，结果必须写入独立报告后才能更新 README/简历中的效果表述。

## 2026-06-23 验收记录

- 默认测试：41 个测试套件，187 个测试，0 failures，0 errors，13 skipped。
- integration Profile：17 个测试套件，108 个测试，0 failures，0 errors，12 skipped。
- Docker 下 MySQL、PGVector、Elasticsearch、Context7 Stdio MCP 和 Exa Streamable HTTP MCP 均完成启动与集成回归。
- live 已验证普通 Harness、项目知识检索、引用回答、当前架构知识样本和 2 个只读 MCP ToolCallback 注入。
- 外部 evidence 调用已处理百炼“思考模式不支持 required tool choice”的兼容边界；关闭该次调用的思考模式后，最终受账户 `free quota exhausted` 阻塞。
- 未运行三组 live evaluation，不产生或宣称任何效果提升数字。
