param(
    [string]$BaseUrl = 'http://127.0.0.1:8090'
)

. (Join-Path $PSScriptRoot 'common.ps1')

Assert-DockerReady
Assert-LocalPostgresStopped

function Invoke-AgentExecute {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Payload
    )

    $response = Invoke-WebRequest -Uri ($BaseUrl.TrimEnd('/') + '/api/v1/agent/execute') `
        -Method Post `
        -ContentType 'application/json' `
        -Headers @{ Accept = 'application/x-ndjson' } `
        -Body ($Payload | ConvertTo-Json -Depth 8) `
        -UseBasicParsing `
        -TimeoutSec 180

    $rawContent = if ($response.Content -is [byte[]]) {
        [System.Text.Encoding]::UTF8.GetString($response.Content)
    } else {
        [string]$response.Content
    }

    $events = New-Object System.Collections.Generic.List[object]
    foreach ($line in ($rawContent -split "(`r`n|`n)")) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed)) {
            continue
        }
        if ($trimmed.StartsWith('data:')) {
            $trimmed = $trimmed.Substring(5).Trim()
        }
        if ($trimmed -eq '[DONE]') {
            continue
        }
        try {
            $events.Add(($trimmed | ConvertFrom-Json))
        } catch {
            throw "无法解析 NDJSON 行：$trimmed"
        }
    }
    return $events
}

function Invoke-AgentExecuteWithRetry {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Payload,
        [Parameter(Mandatory = $true)][string]$Label,
        [int]$MaxAttempts = 6,
        [int]$RetryDelaySeconds = 5
    )

    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        $events = Invoke-AgentExecute -Payload $Payload
        $runId = Get-RunIdFromEvents -Events $events
        $runDetail = Get-RunDetail -RunId $runId

        $chatClientNotReady = $runDetail.status -eq 'FAILED' -and
            -not [string]::IsNullOrWhiteSpace($runDetail.errorMessage) -and
            $runDetail.errorMessage.Contains('ChatClient Bean 不存在')

        if (-not $chatClientNotReady) {
            return @{
                Events = $events
                RunId = $runId
                RunDetail = $runDetail
            }
        }

        if ($attempt -eq $MaxAttempts) {
            throw "$Label 在应用自动装配完成前多次重试仍失败：$($runDetail.errorMessage)"
        }

        Write-Warning "$Label 命中运行时 ChatClient 尚未完成自动装配，$RetryDelaySeconds 秒后重试（$attempt/$MaxAttempts）。"
        Start-Sleep -Seconds $RetryDelaySeconds
    }

    throw "$Label 重试次数已耗尽。"
}

function Assert-EventPresent {
    param(
        [Parameter(Mandatory = $true)][System.Collections.Generic.List[object]]$Events,
        [Parameter(Mandatory = $true)][string]$Value
    )

    $found = $Events | Where-Object {
        $_ -and (
            (($_.PSObject.Properties.Name -contains 'type') -and $_.type -eq $Value) -or
            (($_.PSObject.Properties.Name -contains 'subType') -and $_.subType -eq $Value)
        )
    }
    if (-not $found) {
        throw "未命中预期事件：$Value"
    }
}

function Get-PgScalar {
    param(
        [Parameter(Mandatory = $true)][string]$Sql
    )

    $result = docker exec pgvector psql -U postgres -d ai-agent-station -Atc $Sql 2>$null
    return (($result | Select-Object -First 1) ?? '').Trim()
}

function Get-RunDetail {
    param(
        [Parameter(Mandatory = $true)][string]$RunId
    )

    return Invoke-RestMethod -Uri ($BaseUrl.TrimEnd('/') + "/api/v1/agent/run/$RunId") `
        -Method Get `
        -TimeoutSec 30
}

function Get-RunIdFromEvents {
    param(
        [Parameter(Mandatory = $true)][System.Collections.Generic.List[object]]$Events
    )

    $runId = ($Events | Where-Object {
        $_ -and ($_.PSObject.Properties.Name -contains 'runId') -and -not [string]::IsNullOrWhiteSpace($_.runId)
    } | Select-Object -First 1 -ExpandProperty runId)
    if ([string]::IsNullOrWhiteSpace($runId)) {
        throw '未从流式事件中解析到 runId'
    }
    return $runId
}

function Assert-RunSucceeded {
    param(
        [Parameter(Mandatory = $true)][object]$RunDetail,
        [Parameter(Mandatory = $true)][string]$Label
    )

    if ($RunDetail.status -ne 'SUCCESS') {
        throw "$Label 运行态校验失败，status=$($RunDetail.status)"
    }

    if (-not $RunDetail.steps -or $RunDetail.steps.Count -le 0) {
        throw "$Label 未返回步骤运行记录"
    }
}

Assert-HttpOk -Url ($BaseUrl.TrimEnd('/') + '/actuator/health') -TimeoutSeconds 180

$vectorTotal = [int](Get-PgScalar -Sql "SELECT COUNT(1) FROM vector_store_openai;")
$vectorParentChild = [int](Get-PgScalar -Sql "SELECT COUNT(1) FROM vector_store_openai WHERE metadata::jsonb ->> 'rag_id' = 'rag-agent-station';")
$vectorChildOnly = [int](Get-PgScalar -Sql "SELECT COUNT(1) FROM vector_store_openai WHERE metadata::jsonb ->> 'rag_id' = 'rag-agent-station' AND metadata::jsonb ->> 'chunk_level' = '2';")
if ($vectorTotal -le 0) {
    throw 'PGVector 当前没有可用向量数据。'
}
if ($vectorParentChild -le 0) {
    throw 'PGVector 中未检测到 rag_id=rag-agent-station 的 Parent-Child 子块向量，请先执行 Markdown 导入。'
}
if ($vectorChildOnly -le 0) {
    throw 'PGVector 中未检测到 rag_id=rag-agent-station 的 child chunk 向量记录。'
}

$timestamp = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
$harnessResult = Invoke-AgentExecuteWithRetry -Label 'Harness smoke' -Payload @{
    aiAgentId = 'agent-java-knowledge'
    sessionId = "smoke-harness-$timestamp"
    message = '请把这句话润色得更简洁：受控 Harness 负责限制 Agent 行为。'
    maxStep = 2
}
$harnessEvents = $harnessResult.Events
Assert-EventPresent -Events $harnessEvents -Value 'harness_observation'
Assert-EventPresent -Events $harnessEvents -Value 'complete'
Assert-RunSucceeded -RunDetail $harnessResult.RunDetail -Label 'Harness smoke'

$officialResult = Invoke-AgentExecuteWithRetry -Label '官方文档 evidence smoke' -Payload @{
    aiAgentId = 'agent-java-knowledge'
    sessionId = "smoke-official-$timestamp"
    message = '请核验 Spring AI toolContext 的官方用法，并说明当前项目如何使用。'
    maxStep = 4
}
Assert-EventPresent -Events $officialResult.Events -Value 'rag_evidence'
Assert-EventPresent -Events $officialResult.Events -Value 'complete'
Assert-RunSucceeded -RunDetail $officialResult.RunDetail -Label '官方文档 evidence smoke'

$ragResult = Invoke-AgentExecuteWithRetry -Label 'RAG smoke' -Payload @{
    aiAgentId = 'agent-java-knowledge'
    sessionId = "smoke-rag-$timestamp"
    message = '请仅基于已导入的 Markdown 知识完成回答，不要调用外部 MCP 搜索工具。请回答 Spring AI MCP Client 常见接入方式，并按结论、证据、落地建议输出。'
    maxStep = 4
}
$ragEvents = $ragResult.Events
Assert-EventPresent -Events $ragEvents -Value 'rag_evidence'
Assert-EventPresent -Events $ragEvents -Value 'complete'
$ragRun = $ragResult.RunDetail
Assert-RunSucceeded -RunDetail $ragRun -Label 'RAG smoke'

$memorySessionId = "smoke-memory-$timestamp"
$memoryFirstResult = Invoke-AgentExecuteWithRetry -Label '记忆首轮 smoke' -Payload @{
    aiAgentId = 'agent-java-knowledge'
    sessionId = $memorySessionId
    message = '以后请使用中文简洁回答。请把当前项目的 Agent Runtime 主链路总结成 3 点。'
    maxStep = 2
}
Assert-EventPresent -Events $memoryFirstResult.Events -Value 'context_boundary'
Assert-EventPresent -Events $memoryFirstResult.Events -Value 'complete'
Assert-RunSucceeded -RunDetail $memoryFirstResult.RunDetail -Label '记忆首轮 smoke'

$memorySecondResult = Invoke-AgentExecuteWithRetry -Label '记忆续轮 smoke' -Payload @{
    aiAgentId = 'agent-java-knowledge'
    sessionId = $memorySessionId
    message = '继续补充记忆治理部分。'
    maxStep = 2
}
Assert-EventPresent -Events $memorySecondResult.Events -Value 'context_boundary'
Assert-EventPresent -Events $memorySecondResult.Events -Value 'complete'
Assert-RunSucceeded -RunDetail $memorySecondResult.RunDetail -Label '记忆续轮 smoke'
if ([string]::IsNullOrWhiteSpace($memorySecondResult.RunDetail.contextBoundary.sessionContextSummary)) {
    throw '记忆续轮 smoke 未加载同一 session 的历史摘要。'
}

$legacyEvents = @($harnessEvents) + @($officialResult.Events) + @($ragEvents) |
    Where-Object {
        $_ -and ($_.PSObject.Properties.Name -contains 'subType') -and
            ($_.subType -eq 'tool_routing' -or $_.subType -eq 'context_guard')
    }
if ($legacyEvents) {
    throw '检测到已删除的 tool_routing/context_guard 旧事件。'
}

$currentArchitectureResults = @($ragResult, $memoryFirstResult, $memorySecondResult)
foreach ($result in $currentArchitectureResults) {
    $summary = [string]$result.RunDetail.finalSummary
    if ($summary -match 'GraphRuntime|Flow Plan') {
        throw "检测到旧架构知识污染：$summary"
    }
}

Write-Host '本地 smoke 验证完成：三动作 Harness / 按来源 evidence / 引用回答 / 完整 Turn 记忆均已通过。'
