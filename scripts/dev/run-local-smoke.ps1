param(
    [string]$BaseUrl = 'http://127.0.0.1:8090'
)

. (Join-Path $PSScriptRoot 'common.ps1')

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

function Get-EsCount {
    param(
        [string]$Body = '{"query":{"match_all":{}}}'
    )

    $response = Invoke-RestMethod -Uri 'http://127.0.0.1:9200/ai_rag_chunk/_count' `
        -Method Post `
        -ContentType 'application/json' `
        -Body $Body `
        -TimeoutSec 30
    return [int]$response.count
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
$vectorParentChild = [int](Get-PgScalar -Sql "SELECT COUNT(1) FROM vector_store_openai WHERE metadata::jsonb ->> 'rag_id' = '7001';")
$vectorChildOnly = [int](Get-PgScalar -Sql "SELECT COUNT(1) FROM vector_store_openai WHERE metadata::jsonb ->> 'rag_id' = '7001' AND metadata::jsonb ->> 'chunk_level' = '2';")
if ($vectorTotal -le 0) {
    throw 'PGVector 当前没有可用向量数据。'
}
if ($vectorParentChild -le 0) {
    throw 'PGVector 中未检测到 rag_id=7001 的 Parent-Child 子块向量，请先执行 Markdown 导入。'
}
if ($vectorChildOnly -le 0) {
    throw 'PGVector 中未检测到 rag_id=7001 的 child chunk 向量记录。'
}

$esTotal = Get-EsCount
$esParentChild = Get-EsCount -Body '{"query":{"term":{"rag_id":"7001"}}}'
$esChildOnly = Get-EsCount -Body '{"query":{"bool":{"filter":[{"term":{"rag_id":"7001"}},{"term":{"chunk_level":2}}]}}}'
if ($esTotal -le 0) {
    throw 'Elasticsearch 当前未检测到 ai_rag_chunk 索引数据，请先执行 Markdown 导入。'
}
if ($esParentChild -le 0) {
    throw 'Elasticsearch 中未检测到 rag_id=7001 的 Parent-Child 子块文档。'
}
if ($esChildOnly -le 0) {
    throw 'Elasticsearch 中未检测到 rag_id=7001 的 child chunk 文档。'
}

$timestamp = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
$flowResult = Invoke-AgentExecuteWithRetry -Label 'Flow smoke' -Payload @{
    aiAgentId = '1'
    sessionId = "smoke-flow-$timestamp"
    message = '请把 AI Agent Station 当前主链路整理成 5 条可写进周报的总结。'
    maxStep = 3
}
$flowEvents = $flowResult.Events
Assert-EventPresent -Events $flowEvents -Value 'complete'
$flowRun = $flowResult.RunDetail
Assert-RunSucceeded -RunDetail $flowRun -Label 'Flow smoke'

$toolResult = Invoke-AgentExecuteWithRetry -Label '工具路由 smoke' -Payload @{
    aiAgentId = '1'
    sessionId = "smoke-tool-$timestamp"
    message = '请调研 Spring AI MCP Client 的使用方式，并给出 3 条落地建议。'
    maxStep = 3
}
$toolEvents = $toolResult.Events
Assert-EventPresent -Events $toolEvents -Value 'tool_routing'
Assert-EventPresent -Events $toolEvents -Value 'complete'
$toolRun = $toolResult.RunDetail
Assert-RunSucceeded -RunDetail $toolRun -Label '工具路由 smoke'

$longSuffix = ('请同时保留章节级证据、融合排序、父块回查、Parent-Child、Small-to-Big、rag_evidence、context_guard 等上下文信息。' * 120)
$ragResult = Invoke-AgentExecuteWithRetry -Label 'RAG smoke' -Payload @{
    aiAgentId = '1'
    sessionId = "smoke-rag-$timestamp"
    message = "请仅基于已导入的 Markdown 知识完成回答，不要调用外部 MCP 搜索工具。请回答 Spring AI MCP Client 常见接入方式，并按结论、证据、落地建议输出。$longSuffix"
    maxStep = 3
}
$ragEvents = $ragResult.Events
Assert-EventPresent -Events $ragEvents -Value 'rag_evidence'
Assert-EventPresent -Events $ragEvents -Value 'complete'
$ragRun = $ragResult.RunDetail
Assert-RunSucceeded -RunDetail $ragRun -Label 'RAG smoke'

$contextGuardEvent = $ragEvents | Where-Object { $_.subType -eq 'context_guard' }
if (-not $contextGuardEvent) {
    Write-Warning 'RAG smoke 未触发 context_guard，请考虑降低 AI_AGENT_CONTEXT_MAX_CHARS 或加长输入。'
} else {
    Write-Host 'RAG smoke 已触发 context_guard。'
}

Write-Host '本地 smoke 验证完成：Flow / 工具路由 / RAG 证据链路均已通过。'
