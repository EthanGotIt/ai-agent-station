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

复核记录只写结论、尺寸、端口和提交号，不记录 API key、完整 Prompt、Thinking、用户身份明文或原始订单服务响应。
