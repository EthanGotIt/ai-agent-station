# Commerce Guardian Agent 总体调整计划

本文件是唯一前向路线图。阶段执行卡位于 docs/plans/，实施追踪记录代码与验收证据，.codex/task-handoff.md 只记录当前阶段和唯一下一步。

## 长期目标

Spring AI Agent 负责理解、只读查询、澄清和提交事项；确定性 Java Workflow 负责资格核验、补参、授权、命令创建、执行核验与收尾；Worker 独立执行外部动作。持久化业务事实是恢复和展示依据。

- 退款、催发货和删除最终全部迁移到 Java Workflow；移除 LangGraph4j 和技术图快照运行代码。
- 一个用户请求始终属于一个逻辑 Turn；QuestionCard 补参、Checkpoint 决策和 Command 结果可持久化暂停并恢复原 Turn，不创建回答／决策子 Turn 或自动续跑 Turn。
- 普通新消息默认排入后续 Turn；显式 Steer 可补充当前未结束 Turn。Steer 与结构化补参、授权及 Worker 结果使用不同协议。
- 保留历史；旧流程由兼容路径完成或经用户明确取消，排空后再移除旧执行代码。
- 保留 Items、上下文注入、预算、Tool Result 裁剪和压力触发摘要，真实模型质量单独评测。
- 交付隔离环境可复现的代码、MySQL/订单夹具验收、浏览器验收和回滚演练；生产接入不作为本轮门槛。
- 当前运行时在 Workflow 启动后结束 Agent 工具循环；P8 目标是在原工具调用处持久化暂停，待结构化交互或 Command 结果到达后恢复原 Agent 循环。新目标完成前不得把它描述成当前已交付行为。

不新增多 Agent、跨 Thread 记忆、批量售后、通用工作流设计器或新的业务种类。

## 阶段路线

| 阶段 | 执行卡 | 交付 |
| --- | --- | --- |
| P0 | [计划校准与基线](plans/p0-plan-baseline.md) | 文档统一、工作区归属、可复现基线 |
| P1 | [结算与续跑解耦](plans/p1-settlement-decoupling.md) | Worker 独立持久化、展示业务结果 |
| P2 | [外部结果核验](plans/p2-unknown-outcome.md) | 区分失败、未知、已核验结果 |
| P3 | [迁移催发货](plans/p3-expedite-java-workflow.md) | Java Workflow 与版本恢复 |
| P4 | [迁移退款删除与运行时衔接](plans/p4-refund-delete-runtime.md) | 三类写操作统一确定性执行 |
| P5 | [排空旧流程与退出 LangGraph](plans/p5-retire-langgraph.md) | 历史可读，旧执行代码退出 |
| P6 | [上下文与 Live 评测](plans/p6-context-live-eval.md) | 保留上下文并以证据评测 |
| P7 | [交付验收与回滚演练](plans/p7-release-acceptance.md) | 隔离环境可复现交付 |
| P8 | [Turn 恢复与 WorkflowTask](plans/p8-turn-recovery-workflow-task.md) | 统一原 Turn 的补参、授权、动作等待、Queue／Steer 与结果收尾 |

依赖顺序为 P0→P1→P2→P3→P4→P5→P6→P7→P8。P5 的目标库盘点、P6 的真实模型评测和 P7 的兼容回滚及真实浏览器验收仍是 P8 运行时改造的前置证据；可先完成文档校准和不改变运行行为的命名准备，但不以旧语义验收替代新语义验证。每阶段独立验收、独立回滚；Live 环境准备可提前。

截至 2026-09-28，P0—P4 的本地与隔离验收已通过；P5 的生产执行路径退出、历史读取兼容和只读盘点工具已实现。目标数据库的旧 Run/continuation 归零仍需在具备只读凭据的环境执行盘点后确认；在此证据到位前不宣称线上排空完成。各阶段完成后分别核对暂存差异并提交，不把工作区中更早阶段的改动夹带进阶段提交。

## 兼容与验证原则

保持 HTTP、SSE、Items 分页、QuestionCard/Checkpoint 协议兼容。新状态同步数据库、Codec、DTO、前端和上下文投影，旧数据继续可读。按 Run 不可变编排版本恢复，不按当前开关重解释历史。远程调用置于本地事务外。外部结果未知时不报告成功或失败，不以新幂等身份绕过核验。

当前工作区有未提交代码和文档变化。每阶段开始核对状态与差异，保留并标注已有成果，不将其计为本计划新增工作。每阶段验收通过后建立独立提交，逐路径检查暂存差异，避免夹带既有改动；推送和真实部署仍须遵守当时授权边界。所有模型质量、合成环境、浏览器及部署证据分别记录。

## 自动验证

~~~
python -m scripts.convention_check
python -m unittest discover -s scripts/tests -p "test_*.py"
mvn clean '-DskipTests=false' test
mvn -B '-Pcontext-acceptance,workflow-acceptance' '-DskipTests=false' verify
npm --prefix agent-fronted run typecheck
npm --prefix agent-fronted test
npm --prefix agent-fronted run build
git diff --check
~~~

运行与当前阶段相称的最小门禁；跨模块最终交付执行完整矩阵。
