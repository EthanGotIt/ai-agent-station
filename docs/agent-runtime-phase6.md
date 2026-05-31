# Agent Runtime Phase 6：MCP、Prompt 与记忆治理收敛

## 状态

状态：已完成。代码、SQL seed、本地 DB Prompt、目标单测和完整回归已同步；真实 API/MCP smoke 未执行。

本阶段不引入 OpenAI Agents SDK、LangGraph、CrewAI，也不引入 jtokkit、HuggingFace tokenizer 或 DJL tokenizer。当前目标是让现有 Spring AI + MCP ToolCallback + Flow Plan Runtime 的执行边界更一致，不扩大为框架迁移或 tokenizer 工程。

## MCP 注入时机

Phase 6 将工具使用从“Plan 阶段提前绑定具体工具名”调整为“执行阶段按路由结果注入 ToolCallback”，并删除 Flow Plan / 运行态里的 `toolName` 字段：

- `Step1ToolCapabilityNode` 仍负责本轮 MCP 工具路由和危险工具过滤。
- `AgentPlanValidator` 只校验计划结构、步骤数、依赖和步骤类型，不再校验工具授权集合。
- `Step4PlanExecuteNode` 只为 `LLM` 和 `TOOL` 步骤注入本轮筛选后的 MCP ToolCallback。
- `RAG` 步骤默认不注入外部 MCP 工具，继续走 RAG Advisor 和 `rag_evidence`。
- `SUPERVISION` 和 `SUMMARY` 不注入外部 MCP 工具。
- `toolName` 只保留在 MCP 工具元数据、路由结果和调用期错误 payload 中，不再作为 Plan 字段或步骤运行态字段。

Tool Guard 边界调整为“路由筛选 + 注入前过滤 + 调用期兜底”。`GuardedToolCallback` 继续统一处理不在本轮 allowed set、危险工具、参数错误和工具调用异常。

## Prompt 分层

Planning Prompt 只负责拆解任务：

- 生成 `goal`、`steps`、`dependsOn` 和 `successCriteria`。
- Plan 不输出 `toolName` 字段，工具名只存在于 MCP 工具元数据、路由结果和调用期错误 payload 中。
- 明确计划阶段不要提前绑定具体 MCP 工具。

Step Execution Prompt 负责执行阶段工具决策：

- 可用 MCP 工具已由系统按权限筛选并注入。
- 模型可以按需调用工具，也可以不调用工具直接完成。
- 工具不可用、参数错误或调用失败时，不得编造工具结果。

Supervision Prompt 统一为 JSON 文本结构：`passed / score / issues / suggestions / reason`。当前只稳定输出格式，不新增数据库字段解析。

## 记忆治理边界

当前项目不实现完整长期记忆：

- `MessageWindowChatMemory` 是 session 级短期窗口记忆，服务重启后不保证恢复。
- `ai_agent_run` 和 `ai_agent_step_run` 是运行态记录，不是完整聊天消息表。
- `ContextBoundary` 负责 session 隔离、项目规则、轻量用户偏好边界和摘要注入。
- `ContextWindowGuard` 负责上下文预算和当前 Run 内 step outputs 压缩，不会自动加载同一 session 的历史输入输出。
- `longTermMemoryEnabled=false` 保持不变。

如果后续要让同一 session 跨请求可恢复，建议新增持久化 session 短期记忆：只记录用户输入和最终回答，不把 Planner、Executor、Supervisor 的内部 prompt 写入用户记忆。详见 `docs/agent-runtime-phase7.md`。

> Phase 7 已落地上述方案：新增 `ai_agent_conversation_message`、`AgentConversationMemoryService` 和 `SessionContextAssembler`。本节保留为 Phase 6 收口时的历史边界说明。

Phase 6 借鉴 Spring AI `TokenCountEstimator` 的抽象方式，引入轻量 `ContextUnitEstimator`。默认 `HeuristicContextUnitEstimator` 复用当前中英文估算规则，不绑定真实模型 tokenizer。后续如需要更精确预算，可替换为 Qwen tokenizer 或其他模型 tokenizer 实现。

## 答辩表达

> 本阶段把工具治理从计划层强绑定调整为执行时动态注入：Plan 只描述任务结构，Step4 根据本轮路由结果注入已授权 MCP ToolCallback，由模型在执行阶段自主判断是否调用工具；Tool Guard 在路由、注入和调用期兜底。记忆模块定位为 session 短期记忆和上下文预算治理，不夸大为长期记忆系统。
