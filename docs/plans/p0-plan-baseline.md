# P0：计划校准与可复现基线

## 前置条件与范围

本计划已确认。P0 只校准文档、交接和基线，不改运行行为。

## 执行

1. 记录 git status、diff 统计及已有修改；重点核对运行时、前端、脚本、配置、测试和文档归属。
2. 保留 Items、上下文、LangGraph V1/V2、Worker、Live driver 的既有验证作为历史证据；旧 V2 扩展从前向目标中移出。
3. 更新 README、PRODUCT、架构、运行手册、实施追踪和 handoff，区分当前实现与长期目标。
4. 建立 P0—P7 执行卡并检查相对链接。
5. 运行 Python 规范/单测、Maven 单测、MySQL acceptance 和前端检查。只修复阻塞后续阶段的直接缺陷，记录其他失败及条件。

## 本任务开始时的工作区归属

以下文件在本次实施指令前已存在改动；只把计划和 P0 文档校准计入本次新增工作：

- 前端工作区与投影：ThreadWorkspace、threadProjection、useThreadWorkspace 及其测试。
- App/API/配置/指标：AgentThreadController、AgentModelProperties、Micrometer 指标、application.yml 及相关测试；另有 acceptance 测试和指标测试未跟踪。
- Core 运行时与 V2：AgentRuntimeMetrics、编排版本，以及 Expedite 策略、数据类型和测试。
- Infrastructure Worker/V2：ExternalActionOutcomeManager、LangGraph Engine/Factory、步骤投影及测试；另有上下文摘要、Item Codec、Journal 测试未跟踪。
- Live 与订单夹具：夹具服务、README、测试，runtime_eval runner/scenario/live_runner，以及未跟踪的 live_driver 和测试。
- 协作与证据文档：AGENTS、README、PRODUCT、架构、运行手册、评测、实施追踪、.gitignore；旧 docs/task-handoff.md 已删除，新的 .codex/task-handoff.md 已存在。上述文档差异原先已有，本次只追加目标校准。

重新执行时通过 git status 和 diff 核实本清单，避免把并行或用户后续修改误算成本次工作。

## 验收、回滚、交接

验收：前向目标一致；所有旧改动均保留并分类；基线命令和结果可复现。文档改动按文件回滚，不还原源码改动。完成后 handoff 转 P1；若基线失败，唯一下一步是处理 P1 必需的前置阻塞。
