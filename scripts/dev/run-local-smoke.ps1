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

    $events = New-Object System.Collections.Generic.List[object]
    foreach ($line in ($response.Content -split "(`r`n|`n)")) {
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

function Assert-EventPresent {
    param(
        [Parameter(Mandatory = $true)][System.Collections.Generic.List[object]]$Events,
        [Parameter(Mandatory = $true)][string]$Value
    )

    $found = $Events | Where-Object { $_.type -eq $Value -or $_.subType -eq $Value }
    if (-not $found) {
        throw "未命中预期事件：$Value"
    }
}

function Get-MySqlScalar {
    param(
        [Parameter(Mandatory = $true)][string]$Sql
    )

    $result = docker exec ai-agent-mysql-local mysql -uroot -p123456 -D ai-agent-station -N -e $Sql 2>$null
    return (($result | Select-Object -First 1) ?? '').Trim()
}

function Get-PgScalar {
    param(
        [Parameter(Mandatory = $true)][string]$Sql
    )

    $result = docker exec ai-agent-pgvector-local psql -U postgres -d ai-agent-station -Atc $Sql 2>$null
    return (($result | Select-Object -First 1) ?? '').Trim()
}

function Get-EsCount {
    param(
        [string]$Body = '{"query":{"match_all":{}}}'
    )

    $response = Invoke-RestMethod -Uri 'http://127.0.0.1:19200/ai_rag_chunk/_count' `
        -Method Post `
        -ContentType 'application/json' `
        -Body $Body `
        -TimeoutSec 30
    return [int]$response.count
}

Assert-HttpOk -Url ($BaseUrl.TrimEnd('/') + '/actuator/health') -TimeoutSeconds 90

$parentCount = [int](Get-MySqlScalar -Sql "SELECT COUNT(1) FROM ai_rag_chunk WHERE rag_id = '7001' AND chunk_level = 1;")
$childCount = [int](Get-MySqlScalar -Sql "SELECT COUNT(1) FROM ai_rag_chunk WHERE rag_id = '7001' AND chunk_level = 2;")
if ($parentCount -le 0 -or $childCount -le 0 -or $childCount -le $parentCount) {
    throw "RAG 导入校验失败，父块数=$parentCount，子块数=$childCount"
}

$vectorTotal = [int](Get-PgScalar -Sql "SELECT COUNT(1) FROM vector_store_openai;")
$vectorChildOnly = [int](Get-PgScalar -Sql "SELECT COUNT(1) FROM vector_store_openai WHERE metadata::jsonb ->> 'chunk_level' = '2';")
if ($vectorTotal -le 0 -or $vectorTotal -ne $vectorChildOnly) {
    throw "PGVector 子块索引校验失败，总数=$vectorTotal，child数=$vectorChildOnly"
}

$esTotal = Get-EsCount
$esChildOnly = Get-EsCount -Body '{"query":{"term":{"chunk_level":2}}}'
if ($esTotal -le 0 -or $esTotal -ne $esChildOnly) {
    throw "Elasticsearch 子块索引校验失败，总数=$esTotal，child数=$esChildOnly"
}

$timestamp = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
$flowEvents = Invoke-AgentExecute -Payload @{
    aiAgentId = '1'
    sessionId = "smoke-flow-$timestamp"
    message = '请把 AI Agent Station 当前主链路整理成 5 条可写进周报的总结。'
    maxStep = 3
}
Assert-EventPresent -Events $flowEvents -Value 'complete'

$toolEvents = Invoke-AgentExecute -Payload @{
    aiAgentId = '1'
    sessionId = "smoke-tool-$timestamp"
    message = '请调研 Spring AI MCP Client 的使用方式，并给出 3 条落地建议。'
    maxStep = 3
}
Assert-EventPresent -Events $toolEvents -Value 'tool_routing'
Assert-EventPresent -Events $toolEvents -Value 'complete'

$longSuffix = ('请同时保留章节级证据、融合排序、父块回查、Parent-Child、Small-to-Big、rag_evidence、context_guard 等上下文信息。' * 120)
$ragEvents = Invoke-AgentExecute -Payload @{
    aiAgentId = '1'
    sessionId = "smoke-rag-$timestamp"
    message = "请基于已导入的 Markdown 知识，回答 Spring AI MCP Client 常见接入方式，并按结论、证据、落地建议输出。$longSuffix"
    maxStep = 3
}
Assert-EventPresent -Events $ragEvents -Value 'rag_evidence'
Assert-EventPresent -Events $ragEvents -Value 'complete'

$contextGuardEvent = $ragEvents | Where-Object { $_.subType -eq 'context_guard' }
if (-not $contextGuardEvent) {
    Write-Warning 'RAG smoke 未触发 context_guard，请考虑降低 AI_AGENT_CONTEXT_MAX_CHARS 或加长输入。'
} else {
    Write-Host 'RAG smoke 已触发 context_guard。'
}

$runCount = [int](Get-MySqlScalar -Sql "SELECT COUNT(1) FROM ai_agent_run WHERE session_id IN ('smoke-flow-$timestamp', 'smoke-tool-$timestamp', 'smoke-rag-$timestamp');")
$stepRunCount = [int](Get-MySqlScalar -Sql "SELECT COUNT(1) FROM ai_agent_step_run WHERE run_id IN (SELECT run_id FROM ai_agent_run WHERE session_id IN ('smoke-flow-$timestamp', 'smoke-tool-$timestamp', 'smoke-rag-$timestamp'));")
if ($runCount -lt 3 -or $stepRunCount -le 0) {
    throw "运行态校验失败，run数=$runCount，stepRun数=$stepRunCount"
}

Write-Host '本地 smoke 验证完成：Flow / 工具路由 / RAG 证据链路均已通过。'
