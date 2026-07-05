# Durable After-Sales Agent 面试 Defense

> 当前分支保持 Java 17 基线，已接通轻量 Plan-and-Execute：Spring AI `ChatClient` 生成 JSON Plan，`RefundInformationGatherer` 执行并最多 3 次 RePlan，Spring State Machine 守护 `INTAKE / PENDING_APPROVAL / COMPLETED / REJECTED`。28 条单元测试、30 条真实模型轨迹和 Testcontainers MySQL 集成验收覆盖主链路。

## 一句话定位

> 这是一个基于 Spring AI 2、Spring State Machine 与 spring-ai-community 的可恢复售后退款 Agent。模型只负责 Plan（规划还需要收集什么信息），Java 负责 Execute（执行、校验、RePlan 预算、审批、幂等退款和故障恢复），把模型不确定性约束在受控的信息收集范围内。

## 创新点来自哪里

不是“用了 Spring State Machine”，也不是“升级了 Java 21”。Spring State Machine 提供状态图和恢复语义，Spring AI 提供模型和工具抽象，spring-ai-community 提供记忆与任务清单；Java 21 只可能是运行时优化，不是当前 Agent 创新点。项目自己的部分是：

- 将模型能力收敛为“信息收集规划”：用 JSON Plan 约束模型只输出 `ASK_USER` / `TOOL_CALL(query_order)`；
- 用 `RefundInformationGatheringPolicy` 硬拦截非法 action 与非收敛 Plan，把不确定性关在 Plan 阶段；
- 用确定性 Edge 决定 `REPLAN / ASK_USER / APPROVE / STOP`，最多 3 次 RePlan；
- 把退款工具包装成有幂等键、执行凭证和终态记录的业务 Command；
- 用故障注入评测完整 trajectory，而不只看最终回答。

## Spring AI 与 Spring State Machine 的边界

Spring AI 2 通过 `ChatClient` 让 `RefundPlanningAgent` 输出 JSON Plan；`SpringAiAfterSalesToolAdapter` 使用 `ToolCallingManager + ToolCallback` 执行只读订单查询。模型只参与“还需要收集什么信息”，不参与退款决策或循环控制。Spring State Machine 保存共享状态、调度 Guard/Action、维护内存 checkpoint，并在补信息和退款审批处 interrupt。

项目不使用现成的 ReAct Agent，也不让 `ToolCallingAdvisor` 与状态机同时控制循环。Plan 由 Spring AI 生成，执行与 RePlan 预算由 Java 控制，状态转移由 SSM 负责——三者边界清晰，避免状态、重试和终止存在两个事实来源。

## 工具调用为什么能自愈

Plan 先经过 `RefundInformationGatheringPolicy` 校验：动作白名单、工具白名单、输入 schema 和收敛性。非法 Plan 被替换为确定性兜底。工具执行失败会生成结构化错误信息并带入下一次 PlanningContext，`RefundPlanningAgent` 据此生成下一步 Plan；最多 3 次 RePlan，超过预算由 Java Policy 直接终止。

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

真实模型冻结集 30/30 通过 Tool 契约和治理路由；平均 4554 ms、P95 6862 ms。该数字只代表当前冻结集，不外推为生产成功率。

## 当前不可宣称边界

- 已实现 Spring AI `ChatClient` Plan 生成、`RefundInformationGatherer` 执行与 RePlan、Spring State Machine interrupt/resume、过期 checkpoint 拒绝、退款资格与 RePlan 预算、幂等 Command 和 Outbox 代码路径。
- 已通过 28 条单元测试、30 条真实模型冻结轨迹和 MySQL Testcontainers 集成验收；覆盖 Outbox 重试、Inbox 幂等消费和跨实例恢复。
- 已提供 local/http 订单与退款适配器，但未宣称已与生产支付系统联调。
- Java 17 并发基线为 431.03 tasks/s、P95 82 ms、0 错误，因此当前不升级 Java 21。
- 不宣称多 Agent、长期记忆、通用工作流平台或生产真实退款接入。
- 第一版只验证退款主链路和可替换的售后适配器，不宣称通用平台。
