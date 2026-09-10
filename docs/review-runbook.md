# Commerce Guardian Agent 现场复核手册

这份手册只覆盖本仓库的本机验收，不代表第三方订单平台的生产验收。复核前确认 MySQL 已运行，且当前 PowerShell 会话已经提供 `MYSQL_PASSWORD`、`DEEPSEEK_API_KEY` 等敏感配置；不要把密钥写入脚本、日志或截图。

## 启停与状态

由仓库根目录执行。脚本只记录并操作自己启动的三个明确进程，状态和日志位于系统临时目录 `commerce-guardian-agent-review`；停止时会再次校验记录的命令签名，PID 已被系统回收或命令不匹配时只标记为 `STALE` 并跳过，不执行广泛进程匹配。

```powershell
# 使用独立 SQLite 订单服务，并注入 3 次可重试的催发货失败
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/review/review-services.ps1 `
  -Command start -WithFixture -ExpediteTransientFailures 3

powershell -NoProfile -ExecutionPolicy Bypass -File scripts/review/review-services.ps1 -Command status

# 验收结束后停止前端、Agent 和订单夹具；MySQL 不由脚本停止
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/review/review-services.ps1 -Command stop
```

默认端口为前端 `5173`、Agent `8090`、订单夹具 `18080`。如果端口已有非本脚本进程，先人工核对命令行和归属，不要让脚本接管它。夹具数据库和进程日志可在临时目录查看；不需要时只删除该明确目录中的验收临时数据。

## Week 4 自动验收 runner

服务启动后，先运行不含删除动作的确定性 HTTP 验收：

```powershell
python -m scripts.acceptance `
  --base-url http://127.0.0.1:8090 `
  --order-service-url http://127.0.0.1:18080 `
  --require-expedite-retry
```

runner 会创建新的 Thread，并核对 Thread/Turn/Item 契约、Item 刷新恢复、开放交互重复读取、`clientRequestId` 幂等和执行轨迹回放；随后用独立订单夹具验证物流详情、退款重放和催发货临时失败重试。输出中的 `scenarios=...` 只记录场景名，不保存 Prompt、Thinking、密钥或完整响应。

2026-09-10 HTTP acceptance runner 已在隔离 Agent/订单夹具上完成 Thread/Turn/Item 恢复、开放交互唯一性、刷新恢复、Turn 幂等、执行回放、物流、退款幂等和催发货三次失败后的人工恢复；删除场景仍按默认开关保持 gated。真实 DeepSeek 浏览器路径完成合成订单查询与催发货黄金路径，包含批准、Worker 成功、结果核验和模型续接总结；刷新后事实仍可恢复。Playwright 在 `1920×900`、`1440×900`、`1024×768`、`390×844` 检查输入区、工作台和横向溢出，均通过；另在 `1536×730` 实测离线/在线后的带游标 SSE 重订阅、QuestionCard 空提交焦点和 `role=alert`，控制台 0 errors/0 warnings。深浅主题、reduced-motion、移动对话抽屉和 Escape smoke 已记录。

只有在操作者已经确认夹具数据库属于本次验收且可丢弃时，才执行删除场景：

```powershell
python -m scripts.acceptance `
  --base-url http://127.0.0.1:8090 `
  --order-service-url http://127.0.0.1:18080 `
  --require-expedite-retry `
  --allow-destructive-fixture-actions
```

该开关只允许删除 `ORDER-EXT-DELIVERED-001` 这个夹具订单，并验证同一幂等键重放、订单与物流均返回 404；它不会放开 Agent API 或第三方订单服务的删除权限。未取得确认时，记录 `delete-gated` 即为预期结果。

## 2B-2 快照与真实黄金路径现场记录

本轮在隔离 MySQL `AcceptanceData` 和一次性订单夹具上打开 `AI_AGENT_EXPEDITE_GRAPH_ENABLED=true`。快照故障注入只修改 `AGENT_GRAPH_SNAPSHOT` 或 WorkflowRun 的测试副本，不触碰原始 MySQL `Data` 目录：

- 删除四条技术快照后重启 Agent，批准仍通过业务 Run、Checkpoint 和订单事实重建图；未出现 `FACTS_CHANGED`，快照恢复为有效 `EXPEDITE_GRAPH_V1`，三次注入失败最终收口 `MANUAL_RETRY_REQUIRED`，业务变更为 0。
- 将四条快照的 `STATE_JSON` 损坏后重启，原坏行被安全跳过并从业务事实重建；将快照 `ORCHESTRATION_VERSION` 改为旧值后重启，版本失配同样不授予授权，图从业务事实重建。
- 将 WorkflowRun 编排版本改为未知值后批准，决策 Turn 失败并写入 `UNKNOWN_WORKFLOW_ORCHESTRATION_VERSION`，不创建 ExternalActionCommand，Run 保持等待状态；未知版本不回退到旧图。
- 在零注入失败的干净夹具上完成“查询 → 催发货 → 批准 → Worker → 结果核验 → 模型总结”：Run `COMPLETED`，命令一次成功，订单物流为 `EXPEDITE_REQUESTED`，幂等记录和业务变更各为 1。

以上现场证据只记录受控状态、计数和稳定标识，不保存 Prompt、Thinking、密钥或完整模型响应。生产开关、第三方订单平台鉴权和删除动作仍需按部署环境单独验收。

## 三条黄金路径

### 1. 物流问题闭环

1. 在工作台输入“查物流三天没有更新的订单”，等待订单事实卡片出现。
2. 在对应订单卡片点击“查物流”；确认只发出一次确定性 `QUERY_LOGISTICS` 请求，不修改输入框、不生成模拟问答。
3. 验证原订单卡片内出现物流时间线、真实 `LOGISTICS_TIMELINE` Item 和完成耗时；右侧“运行详情”只在需要时查看完整 Item 序列。
4. 刷新页面或切换 Thread，确认卡片位置和物流事实由持久化 Item 恢复，旧订单不会显示到另一 Turn。

### 2. 退款取消、删除与授权

1. 对可退款订单点击“申请退款”，在居中的 QuestionCard 中不填写必填原因，点击“结束本次操作”。确认不会触发表单必填校验，Workflow 进入 `REJECTED`，没有 `EXTERNAL_ACTION_COMMAND` 或退款业务变更。
2. 刷新页面，确认已结束 QuestionCard 不会重新占用底部输入区；订单卡片仍可再次点击“申请退款”。
3. 再次发起退款，填写原因，点击“继续”直到最终确认；最终确认前检查卡片显示“确认并执行”，拒绝仍是无副作用终态。
4. 授权后验证原订单卡片出现外部动作回执和最新 `ORDER_DETAIL`/物流事实。若后置核验失败，回执应显示“操作已受理、最新状态暂未核验”，并提供可编辑的重新查询入口。
5. 对测试订单点击“删除记录”，在独立执行确认卡中核对订单号、删除范围和“不可恢复”提示；批准后确认订单详情返回 404、物流轨迹为空，页面只显示“记录已删除”，不再提供隐藏或恢复按钮。

### 3. 催发货重试与人工恢复

1. 使用 `-ExpediteTransientFailures 3` 启动夹具，对 `ORDER-EXT-TODAY-001` 点击“催发货”并完成最终授权。
2. 在原订单卡片内观察三次重试：显示当前尝试次数、下一状态和最终“需要人工重试”，而不是新增一组问答或全局弹窗。
3. 点击“人工重试”，等待夹具第 4 次请求成功。访问 `http://127.0.0.1:18080/_fixture/stats`，确认 `injectedFailures=3`、`businessMutations=1`，同一幂等键只产生一次真实变更。
4. 刷新页面，确认失败事实仍保留，最终回执和最新订单物流事实折叠在最初订单卡片；右侧 Item 检查器可看到完整重试序列。

## 浏览器验收矩阵

至少复核 `1920×900`、`1440×900`、`1024×768` 和 `390×844`。每个尺寸检查：底部输入框常驻、中央阅读列不被输入框遮挡、1024px 使用右侧抽屉、移动端检查器可关闭且锁定背景滚动；同时切换深浅主题、键盘 Tab/Enter/Esc、`prefers-reduced-motion`，并确认错误状态有焦点和 `role=alert`。

每次复核按下表记录结论，未实际打开浏览器时保持 `pending`，不可用组件测试结果替代：

| 视口 | 主题 | 键盘/Esc | reduced-motion | SSE 重连 | 刷新恢复 | 错误焦点 | 结论/提交 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1920×900 | light/dark smoke | 移动抽屉 Esc 已验 | reduce smoke | pending | 已通过 | pending | 查询与布局 smoke 通过；专项矩阵证据见 1536×730 |
| 1440×900 | light/dark smoke | 移动抽屉 Esc 已验 | reduce smoke | pending | 已通过 | pending | 布局与刷新恢复通过；专项矩阵待同尺寸补录 |
| 1024×768 | light/dark smoke | 移动抽屉 Esc 已验 | reduce smoke | pending | 已通过 | pending | 抽屉布局通过；专项矩阵待同尺寸补录 |
| 390×844 | light/dark smoke | 移动抽屉 Esc 已验 | reduce smoke | pending | 已通过 | pending | 移动布局通过；专项矩阵待同尺寸补录 |
| 1536×730 | light/dark smoke | Enter/Esc 已验 | reduce smoke | offline→online 后 `events?afterSequence=18` 重订阅 | 已通过 | 空提交后 `active + invalid + role=alert` | 本轮浏览器证据；控制台 0 errors/0 warnings |

复核记录只写结论、尺寸、端口和提交号，不记录 API key、完整 Prompt、Thinking、用户身份明文或原始订单服务响应。
