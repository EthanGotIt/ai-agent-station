# 售后 Agent 真实模型冻结轨迹评估

## 当前规划契约复验

- 执行时间：2026-07-10（Asia/Shanghai）
- 数据集：`after-sales-trajectory-v1`，30 条
- 模型：Plan/Replan 使用 `deepseek-v4-pro`；Execute 直接执行经 Policy 校验的只读证据步骤
- 模型规划契约通过：30/30（100%）
- Java 治理路由通过：30/30（100%）
- 平均延迟：5822 ms；P50：5730 ms；P95：8203 ms

## 历史记录

- 执行时间：2026-07-07（Asia/Shanghai）
- 数据集：`after-sales-trajectory-v1.jsonl`
- 模型：本记录在 2026-07-07 使用 `deepseek-v4-pro` 进行 Plan/Replan、`deepseek-v4-flash` 进行 Execute；当前运行时仅在 Plan/Replan 调用模型，Execute 只执行已校验的只读步骤
- 真实模型调用：30 次
- 模型规划契约通过：30/30（100%）
- Java 治理路由通过：30/30（100%）
- 平均延迟：1239 ms
- P50 延迟：1114 ms
- P95 延迟：2081 ms
- 失败：0

评估同时验证两层边界：

1. **模型规划契约**：`RefundPlanningAgent` 生成的 JSON Plan 必须只包含白名单动作（`ASK_USER` / `TOOL_CALL`），工具仅限 `query_order`，不生成退款、审批等副作用动作。
2. **Java 治理路由**：`RefundInformationGatherer` 执行 Plan 后，由 `AfterSalesRefundEligibilityPolicy` 与 Spring State Machine 决定最终到达 `INTAKE` / `PENDING_APPROVAL` / `COMPLETED` / `REJECTED` 哪一个状态。

在线测试默认关闭，只有显式设置 `live.after-sales.evaluation.enabled=true` 才会产生外部调用。

```powershell
mvn verify -pl ai-agent-station-app -am "-DskipTests=false" `
  "-Dit.test=AfterSalesLiveModelEvaluationIT" `
  "-Dlive.after-sales.evaluation.enabled=true"
```

> 版本说明：历史记录反映 2026-07-07 的评测实现。当前 `RefundPlanningAgent` 只调用 `ChatClient` 生成结构化 Plan，`SpringAiAfterSalesToolAdapter` 已改为直接执行经 Policy 校验的只读证据步骤，不再二次调用模型。
