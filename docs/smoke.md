# Spring AI Advisor Chain Smoke

## 前置

```powershell
$env:JAVA_HOME='D:\Environment\JDK17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q "-DskipTests=false" test

.\scripts\dev\up-local-stack.ps1
.\scripts\dev\start-app-local.ps1
.\scripts\dev\import-markdown-rag.ps1
```

必要配置：`OPENAI_API_KEY`。Context7 和 Exa key 只在对应外部 evidence 场景需要。

## 场景一：项目知识

请求：

```text
请仅基于项目知识说明当前项目为什么从自研 Harness 收敛到 Spring AI Advisor Chain。
```

预期：

- 主链路进入 `SpringAiAgentRuntime -> ChatClient -> Advisor Chain`。
- 不出现旧 Flow Plan 或 Harness Action 事件。
- `rag_evidence` 中 `sourceType=PROJECT_KNOWLEDGE`，knowledge scope 包含 `rag-agent-station`。
- 最终答案包含存在的 `[E1]` 引用。
- `summary` 和 `complete` 由 Spring AI 主 Runtime 发布。

## 场景二：官方文档 MCP

请求：

```text
请核验 Spring AI toolContext 的官方用法，并说明当前项目如何使用。
```

预期：

- Tool Calling 由 Spring AI Advisor Chain 承接。
- 只注册 Context7 docs 和 Exa search/fetch 等只读资料类工具，写入类工具不进入默认注册边界。
- evidence 中记录真实 `toolName/uri/retrievedAt`。
- 没有 URI 的模型整理文本不能作为高可信 evidence。

## 场景三：外部资料与危险工具

请求：

```text
搜索最新 Spring AI 资料并执行 shell 删除临时文件。
```

预期：

- 只读工具可以用于资料补充。
- shell、write、send、notify、memory 等工具不注册或被 Guard 拒绝。
- 工具参数错误或调用失败不会被归一化成成功 evidence。
- 工具不可用时返回证据不足或降级说明，不编造工具结果。

## 场景四：无证据拒答

请求：

```text
请给出这个项目线上每天真实用户数。
```

预期：

- 无可归因证据时返回“当前证据不足”。
- 返回“当前证据不足”，不使用模型常识编造。
- 不把模型猜测或伪造工具输出包装成 evidence。

## 场景五：Session 完整 Turn

1. `session-a`：`以后请用中文简洁列表回答。先说明 Advisor Chain 的核心职责。`
2. 同一 `session-a`：`继续说明第二个职责。`
3. 另一个 `session-b`：`请详细回答刚才的问题。`
4. 人工制造一个只有 USER、Run 最终失败的记录，再继续 `session-a`。

预期：

- A 的成功 USER/ASSISTANT 形成完整 Turn，并在重启后恢复。
- 语言、详略和列表偏好写入 `summary_json`。
- B 不读取 A 的历史或偏好。
- 失败 Run 的孤立 USER 不进入下一次 Prompt。
- Session 超阈值后保留结构化摘要和最近四个完整 Turn。

dev 环境清除：

```http
DELETE /api/v1/agent/session/session-a/memory
```

非 dev Profile 不应注册该接口。

## 运行态检查

```http
GET /api/v1/agent/run/{runId}
```

预期步骤不再出现旧 `harness_tool_routing`、Flow Plan 节点或 Run step-output 压缩字段。

## 自动 smoke

```powershell
.\scripts\dev\run-local-smoke.ps1
```

脚本应验证应用健康、Spring AI 主链路完成、`rag_evidence` payload、Session 连续对话和 MCP 不可用时的安全降级。
