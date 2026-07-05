# 售后 Agent 真实模型冻结轨迹评估

- 执行时间：2026-07-03（Asia/Shanghai）
- 数据集：`after-sales-trajectory-v1.jsonl`
- 模型：由本地 `OPENAI_MODEL` 配置提供，报告不记录凭据
- 真实模型调用：30 次
- 模型规划契约通过：30/30（100%）
- Java 治理路由通过：30/30（100%）
- 平均延迟：4554 ms
- P50 延迟：4474 ms
- P95 延迟：6862 ms
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
