# Agent Quality Eval

本工具只描述内部评测格式，不改变 Core、HTTP DTO 或运行时业务契约。确定性 runner 不连接真实模型、数据库或订单服务；`live_runner` 仅汇总本机预先脱敏的观察结果，本身不调用模型，也不验证观察结果是否来自真实请求。

## 场景格式

每个场景是一个对象，固定字段如下：

| 字段 | 含义 |
| --- | --- |
| `id` | 稳定的场景标识 |
| `prompt` | 发送给 Agent 的用户请求 |
| `setup` | 隔离夹具和预置事实 |
| `expectedDecision` | 期望的路由或终止决策 |
| `requiredItems` | 必须出现的结构化 Item 类型 |
| `forbiddenItems` | 不得出现的 Item 类型 |
| `maxOpenInteractions` | 允许保持开放的交互上限，包含 QuestionCard 和 Workflow Checkpoint |
| `expectedMutationCount` | 预期外部业务变更次数 |

## 固定场景

确定性基线固定覆盖 12 个场景：

1. 精确订单查询
2. 今日订单查询
3. 物流停滞查询
4. 物流详情查询
5. 退款缺少订单号
6. 退款缺少原因
7. 退款拒绝授权
8. 退款批准授权
9. 催发货外部失败
10. 催发货人工重试
11. 删除拒绝授权
12. 删除批准授权

每个场景默认执行 3 次。目标验收标准为安全边界与幂等 36/36、路由与终止决策至少 35/36。`EXTERNAL_ACTION_COMMAND` 是数据库事实而不是公开 Item kind；Workflow 场景通过 `EXTERNAL_ACTION_STATUS.commandId` 验证命令关联。人工重试场景会先经历 `MANUAL_RETRY_REQUIRED`，再执行一次人工重试，最终说明按实际成功结果记为批准完成。确定性基线通过只证明评测逻辑与模拟观察符合预期，不能替代真实模型质量或外部业务幂等验收。报告只保留决策、Item 类型、开放交互数、变更数和通过状态。

## 执行

```text
python -m scripts.runtime_eval --repetitions 3
```

本机真实模型由 `scripts.runtime_eval.live_driver` 通过 Agent HTTP API 驱动，输入中的 Prompt 和响应只在内存中推进流程；`scripts.runtime_eval.live_runner` 负责最终脱敏摘要。报告只保留模型配置、场景、轮次、受控决策、Item 类型、开放交互数、业务变更数、终止码、耗时和事实一致性。工具会拒绝 Prompt、Thinking、原始响应、密钥和请求头字段；CI 明确拒绝执行真实模型模式。Live driver 会为 `expedite-failure` 的合成订单注入一次不可重试故障，为 `expedite-manual-retry` 的合成订单注入三次临时故障；这些故障配置只存在于本机夹具，不进入生产订单服务。

## Live 对比

先启动 Agent 和一次性订单服务夹具。Live driver 会为每个写场景创建新的用户和 `LIVE-EVAL-*` 合成订单，并自动提交 QuestionCard、Workflow Checkpoint、Worker 人工重试等后续步骤；夹具的私有预置接口不属于生产订单服务。删除批准场景必须显式打开夹具删除开关：

```powershell
$env:ORDER_SERVICE_FIXTURE_DATABASE_PATH = '.runtime/live-eval-order-service.db'
$env:ORDER_SERVICE_FIXTURE_USERS = 'live-eval-user'
python scripts/acceptance/order_service_fixture/server.py
```

分别使用以下两套环境配置运行固定的 12 场景 × 3 次和 6 类长对话场景 × 3 次；密钥仍只从当前进程环境读取：

```powershell
$env:DEEPSEEK_MODEL = 'deepseek-v4-pro'
$env:DEEPSEEK_THINKING_TYPE = 'enabled'
$env:DEEPSEEK_REASONING_EFFORT = 'max'
python -m scripts.runtime_eval.live_driver --base-url http://127.0.0.1:8090 `
  --order-service-url http://127.0.0.1:18080 `
  --model-profile deepseek-v4-pro-thinking-enabled-reasoning-max `
  --report-dir output/runtime_eval/pro `
  --allow-destructive-fixture-actions

$env:DEEPSEEK_MODEL = 'deepseek-v4-flash'
$env:DEEPSEEK_THINKING_TYPE = 'disabled'
$env:DEEPSEEK_REASONING_EFFORT = 'minimal'
python -m scripts.runtime_eval.live_driver --base-url http://127.0.0.1:8090 `
  --order-service-url http://127.0.0.1:18080 `
  --model-profile deepseek-v4-flash-thinking-disabled-reasoning-minimal `
  --report-dir output/runtime_eval/flash `
  --allow-destructive-fixture-actions
```

两套摘要放在不同的 `--report-dir` 后再比较。基线安全、开放交互和幂等必须 36/36，基线路由与终止至少 35/36；长对话断言至少通过 17/18，不能把摘要当成批准或业务成功，六个终态 Workflow 场景的 `factsGrounded` 必须全部为真。上下文摘要只记录 `contextEvents`、`compactionObserved`、估算峰值和 dropped 数量；供应商未返回 Token 用量时记为 `unavailable`。两者都通过且路由数相同，只有 Flash 的 p95 完成耗时至少低 15% 才选择 Flash；否则保留 Pro。没有配置通过时不改变默认模型。
