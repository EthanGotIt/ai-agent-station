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
$mcpHealth = Invoke-RestMethod -Uri ($BaseUrl.TrimEnd('/') + '/actuator/health/mcpClients') -Method Get -TimeoutSec 30
if ($mcpHealth.status -ne 'UP') {
    throw "MCP health 端点状态异常，status=$($mcpHealth.status)"
}
if (-not $mcpHealth.details -or -not ($mcpHealth.details.PSObject.Properties.Name -contains 'availability')) {
    throw 'MCP health 端点未返回 availability 明细。'
}

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
$graphResult = Invoke-AgentExecuteWithRetry -Label 'GraphRuntime smoke' -Payload @{
    aiAgentId = '1'
    sessionId = "smoke-flow-$timestamp"
    message = '请把 AI Agent Station 当前主链路整理成 5 条可写进周报的总结。'
    maxStep = 3
}
$graphEvents = $graphResult.Events
Assert-EventPresent -Events $graphEvents -Value 'graph_lifecycle'
Assert-EventPresent -Events $graphEvents -Value 'complete'
$graphRun = $graphResult.RunDetail
Assert-RunSucceeded -RunDetail $graphRun -Label 'GraphRuntime smoke'

$toolResult = Invoke-AgentExecuteWithRetry -Label '工具路由 smoke' -Payload @{
    aiAgentId = '1'
    sessionId = "smoke-tool-$timestamp"
    message = '请调研 Spring AI MCP Client 的使用方式，并给出 3 条落地建议。'
    maxStep = 3
}
$toolEvents = $toolResult.Events
Assert-EventPresent -Events $toolEvents -Value 'tool_routing'
Assert-EventPresent -Events $toolEvents -Value 'graph_lifecycle'
Assert-EventPresent -Events $toolEvents -Value 'complete'
$toolLifecycle = $toolEvents | Where-Object {
    $_ -and ($_.PSObject.Properties.Name -contains 'subType') -and $_.subType -eq 'graph_lifecycle'
} | Select-Object -First 1
if (-not $toolLifecycle.payload -or
    -not ($toolLifecycle.payload.PSObject.Properties.Name -contains 'toolResolutionMillis') -or
    -not ($toolLifecycle.payload.PSObject.Properties.Name -contains 'injectedToolCount') -or
    -not ($toolLifecycle.payload.PSObject.Properties.Name -contains 'mcpClients')) {
    throw 'graph_lifecycle 未返回 MCP 工具解析摘要。'
}
$toolRun = $toolResult.RunDetail
Assert-RunSucceeded -RunDetail $toolRun -Label '工具路由 smoke'

$ragResult = Invoke-AgentExecuteWithRetry -Label 'RAG smoke' -Payload @{
    aiAgentId = '1'
    sessionId = "smoke-rag-$timestamp"
    message = '请仅基于已导入的 Markdown 知识完成回答，不要调用外部 MCP 搜索工具。请调用 rag_search 查询 Spring AI MCP Client 常见接入方式，并按结论、证据、落地建议输出。'
    maxStep = 3
}
$ragEvents = $ragResult.Events
Assert-EventPresent -Events $ragEvents -Value 'rag_evidence'
Assert-EventPresent -Events $ragEvents -Value 'complete'
$ragRun = $ragResult.RunDetail
Assert-RunSucceeded -RunDetail $ragRun -Label 'RAG smoke'

$memorySessionId = "smoke-memory-$timestamp"
$memoryFirstResult = Invoke-AgentExecuteWithRetry -Label '记忆首轮 smoke' -Payload @{
    aiAgentId = '1'
    sessionId = $memorySessionId
    message = '以后请使用中文简洁回答。请把当前项目的 Agent Runtime 主链路总结成 3 点。'
    maxStep = 2
}
Assert-EventPresent -Events $memoryFirstResult.Events -Value 'context_boundary'
Assert-EventPresent -Events $memoryFirstResult.Events -Value 'complete'
Assert-RunSucceeded -RunDetail $memoryFirstResult.RunDetail -Label '记忆首轮 smoke'

$memorySecondResult = Invoke-AgentExecuteWithRetry -Label '记忆续轮 smoke' -Payload @{
    aiAgentId = '1'
    sessionId = $memorySessionId
    message = '继续补充记忆治理部分。'
    maxStep = 2
}
Assert-EventPresent -Events $memorySecondResult.Events -Value 'context_boundary'
Assert-EventPresent -Events $memorySecondResult.Events -Value 'complete'
Assert-RunSucceeded -RunDetail $memorySecondResult.RunDetail -Label '记忆续轮 smoke'
$conversationScope = $memorySecondResult.RunDetail.contextBoundary.conversationScope
if ($conversationScope -ne 'postgres_graph_checkpoint') {
    throw "记忆续轮 smoke 未使用 PostgreSQL Graph checkpoint，conversationScope=$conversationScope"
}
$firstBoundary = $memoryFirstResult.Events | Where-Object {
    $_ -and ($_.PSObject.Properties.Name -contains 'subType') -and $_.subType -eq 'context_boundary'
} | Select-Object -First 1
$secondBoundary = $memorySecondResult.Events | Where-Object {
    $_ -and ($_.PSObject.Properties.Name -contains 'subType') -and $_.subType -eq 'context_boundary'
} | Select-Object -First 1
$firstThreadId = if ($firstBoundary -and
    ($firstBoundary.PSObject.Properties.Name -contains 'payload') -and
    ($firstBoundary.payload.PSObject.Properties.Name -contains 'threadId')) {
    [string]$firstBoundary.payload.threadId
} else {
    ''
}
$secondThreadId = if ($secondBoundary -and
    ($secondBoundary.PSObject.Properties.Name -contains 'payload') -and
    ($secondBoundary.payload.PSObject.Properties.Name -contains 'threadId')) {
    [string]$secondBoundary.payload.threadId
} else {
    ''
}
if (-not $firstBoundary -or -not $secondBoundary -or
    [string]::IsNullOrWhiteSpace($firstThreadId) -or
    $firstThreadId -ne $secondThreadId) {
    throw '记忆续轮 smoke 未复用同一 Graph threadId。'
}
$graphThreadCount = [int](Get-PgScalar -Sql 'SELECT COUNT(1) FROM GraphThread WHERE is_released = FALSE;')
$graphCheckpointCount = [int](Get-PgScalar -Sql 'SELECT COUNT(1) FROM GraphCheckpoint;')
if ($graphThreadCount -le 0 -or $graphCheckpointCount -le 0) {
    throw "Graph checkpoint 未写入，threads=$graphThreadCount checkpoints=$graphCheckpointCount"
}

Write-Host '本地 smoke 验证完成：GraphRuntime / MCP health / 工具路由 / RAG 证据 / PostgreSQL session checkpoint 均已通过。'
