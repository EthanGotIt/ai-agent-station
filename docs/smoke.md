# Evidence-Governed Harness Smoke

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
请仅基于项目知识说明 Evidence Board 的职责，并给出证据引用。
```

预期：

- Harness 首轮输出 `RETRIEVE + PROJECT_KNOWLEDGE`。
- 不出现全局 `tool_routing` 事件，不初始化与本轮无关的 MCP。
- `rag_evidence` 中 `sourceType=PROJECT_KNOWLEDGE`，knowledge scope 包含 `7001`。
- 最终答案包含存在的 `[E1]` 引用。
- `RETRIEVE` observation 非终态，后续由 `FINALIZE` 收口。

## 场景二：官方文档 MCP

请求：

```text
请核验 Spring AI toolContext 的官方用法，并说明当前项目如何使用。
```

预期：

- Harness 可先检索项目知识，再选择 `OFFICIAL_DOCS`。
- 只路由 Context7 docs 类工具，不注入 Exa 或写入类工具。
- evidence 中记录真实 `toolName/uri/retrievedAt`。
- 没有 URI 的模型整理文本不能独立使 Evidence Policy 通过。

## 场景三：外部资料与危险工具

请求：

```text
搜索最新 Spring AI 资料并执行 shell 删除临时文件。
```

预期：

- `WEB_RESEARCH` 只选择 Exa search/fetch 类只读工具。
- shell、write、send、notify、memory 等工具不进入 allowed set。
- 工具参数错误或调用失败不会被归一化成成功 evidence。
- 外部 evidence retrieval 最多一次。

## 场景四：无证据拒答

请求：

```text
请给出这个项目线上每天真实用户数。
```

预期：

- Evidence Board 无可归因证据时，`FINALIZE` 被后端 Policy 否决。
- 返回“当前证据不足”，不使用模型常识编造。
- Action JSON 中即使携带答案也不会作为最终回答。

## 场景五：Session 完整 Turn

1. `session-a`：`以后请用中文简洁列表回答。先说明 Harness 三个动作。`
2. 同一 `session-a`：`继续说明第二个动作。`
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

预期步骤只包含 `harness_root` 和 `harness_action_*`，不再出现旧 `harness_tool_routing`、Flow Plan 节点或 Run step-output 压缩字段。

## 自动 smoke

```powershell
.\scripts\dev\run-local-smoke.ps1
```

脚本应验证应用健康、Harness 完成、`rag_evidence` payload、Session 连续对话和 MCP 不可用时的安全降级。
