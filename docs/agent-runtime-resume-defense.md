# Durable After-Sales Agent 面试 Defense

> 当前分支保持 Java 17 基线，已接通低层 Tool Calling、治理图、interrupt/resume、MysqlSaver、审批与幂等退款/Outbox。17 条聚焦测试、30 条冻结轨迹和 Testcontainers MySQL 跨实例恢复测试已通过；阻塞 I/O 节点使用专用有界执行器与 Graph/Policy 线程隔离。

## 一句话定位

> 这是一个基于 Spring AI 2 与 LangGraph4j 的可恢复售后退款 Agent。它把模型的不确定性显式变成图状态，由 Java Policy 控制参数修复、有限重试、人工审批、幂等退款和故障恢复。

## 创新点来自哪里

不是“用了 LangGraph4j”，也不是“升级了 Java 21”。LangGraph4j 提供图和 checkpoint，Spring AI 提供模型和工具抽象；Java 21 只可能是运行时优化，不是当前 Agent 创新点。项目自己的部分是：

- 将 `tool_error / retry_budget / approval / terminal_reason` 建模为显式状态；
- 用确定性 Edge 决定 `REPAIR / RETRY / CLARIFY / APPROVE / STOP`；
- 把退款工具包装成有幂等键、执行凭证和终态记录的业务 Command；
- 用故障注入评测完整 trajectory，而不只看最终回答。

## Spring AI 与 LangGraph4j 的边界

Spring AI 2 使用低层 `ChatModel + ToolCallingManager + ToolCallback`。模型可以生成只读工具请求，但 Spring AI 不自动跑完整循环。LangGraph4j 保存共享状态、调度节点、持久化 checkpoint，并在补信息和退款审批处 interrupt。

项目不使用 LangGraph4j 的现成 ReAct Agent，也不让 `ToolCallingAdvisor` 与 Graph 同时控制循环。否则状态、重试和终止会存在两个事实来源，无法可靠恢复。

## 工具调用为什么能自愈

工具请求先经过 schema 和业务字段校验。参数错误会生成结构化 `ToolErrorEnvelope`，只把字段错误、允许 schema 和剩余预算反馈给修复节点，最多修复两次。超时和限流保持原参数重试；状态冲突重新读取订单；无权限和业务拒绝直接终止。

相同参数指纹连续失败会提前停止，避免模型只换一种表达却重复提交同一错误调用。退款副作用不交给模型选择，必须经过资格 Policy 和人工审批节点。

## checkpoint 和幂等分别解决什么

checkpoint 解决“运行到哪、恢复后从哪个节点继续”；业务幂等解决“节点被重放时会不会再次退款”。两者不能互相替代。

退款使用稳定 `caseId:REFUND` 幂等键。即使审批请求重复、进程在退款成功后响应前宕机，恢复节点也只能读取已有 Command 结果，不能再次调用退款适配器。

## 为什么当前回到 Java 17

当前机器 `JAVA_HOME` 是 Java 17，而且项目亮点主要来自可恢复治理图、工具契约自愈、人工审批和幂等副作用，不来自线程模型。为了避免把环境升级包装成 Agent 能力，当前分支保持 Java 17。

阻塞模型调用、JDBC、同步 HTTP Tool 和退款核验仍通过专用 `agentIoExecutor` 隔离，但实现为 Java 17 有界线程池。后续如果要升级 Java 21，必须拿出并发压测、连接池容量和线程观测证据，再把虚拟线程作为执行层优化，而不是面试主卖点。

## 怎么证明不是框架 Demo

评测不只检查回答文本，而是冻结正常、参数错误、超时、状态冲突、重复审批和宕机恢复轨迹，对比自由工具循环与治理图：

- 工具参数合法率和错误恢复率；
- 未审批、跨用户和重复退款次数；
- checkpoint 恢复前后结果一致性；
- 模型调用数、P95 延迟和终态原因。

在 Full 评测完成前，只能讲架构和测试覆盖，不能编造成功率提升数字。

## 当前不可宣称边界

- 已实现 Spring AI `ChatModel + ToolCallingManager` 受控调用、LangGraph4j interrupt/resume、过期 checkpoint 拒绝、退款资格与错误预算、幂等 Command 和 Outbox 代码路径。
- 已通过 17 条聚焦测试、30 条冻结轨迹和 MySQL Testcontainers 跨实例恢复；集成测试断言恢复后只有一条退款 Command、一条 Outbox，订单终态为 `REFUNDED`。
- 当前只接本地演示订单，不宣称真实支付接入；真实模型 Full 评测未执行，不提供效果提升数字。
- 当前保持 Java 17；Java 21/虚拟线程未作为当前已落地能力，生产负载下的容量压测也尚未执行。
- 不宣称多 Agent、长期记忆、通用工作流平台或生产真实退款接入。
- 第一版只验证退款主链路和本地可控的售后适配器。
