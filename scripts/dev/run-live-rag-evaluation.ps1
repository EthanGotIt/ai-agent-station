param(
    [string]$JavaHome,
    [string]$OutputDirectory,
    [string]$CaseIds
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$repoRoot = Get-RepoRoot
Set-LocalAppEnvironment -JavaHome $JavaHome
Assert-DockerReady
Assert-LocalPostgresStopped
Assert-TcpPortReady -TargetHost '127.0.0.1' -Port 5432 -TimeoutSeconds 30

foreach ($key in @('OPENAI_API_KEY', 'CONTEXT7_API_KEY', 'EXA_API_KEY')) {
    $value = [Environment]::GetEnvironmentVariable($key)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "完整 live evaluation 缺少环境变量：$key"
    }
    if ($value.Trim().StartsWith('${')) {
        throw "完整 live evaluation 的环境变量仍是未解析占位符：$key"
    }
}

$env:RUN_LIVE_RAG_EVALUATION = 'true'
$env:AI_AGENT_ES_INIT_ON_STARTUP = 'false'
$env:RAG_EVAL_CASE_IDS = $CaseIds
$env:AI_AGENT_MODEL_REQUEST_TIMEOUT_SECONDS = if ($env:AI_AGENT_MODEL_REQUEST_TIMEOUT_SECONDS) {
    $env:AI_AGENT_MODEL_REQUEST_TIMEOUT_SECONDS
} else {
    '45'
}
$env:AI_AGENT_MODEL_MAX_RETRIES = '0'
$output = if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    Join-Path $repoRoot 'target\evaluation'
} elseif ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory
} else {
    Join-Path $repoRoot $OutputDirectory
}
$output = [System.IO.Path]::GetFullPath($output)

Push-Location $repoRoot
try {
    & mvn -q -pl ai-agent-station-app -am -Pintegration `
        '-DskipTests=false' `
        '-Dfailsafe.failIfNoSpecifiedTests=false' `
        '-Dit.test=RagLiveEvaluationIT' `
        "-Devaluation.output=$output" `
        verify
    if ($LASTEXITCODE -ne 0) {
        throw '三组 RAG live evaluation 执行失败。'
    }

    $report = Join-Path $output 'rag-evaluation-v1-live.json'
    if (-not (Test-Path $report)) {
        throw "评测命令结束但未生成报告：$report"
    }
    Write-Host "三组 RAG live evaluation 完成：$report"
} finally {
    Pop-Location
}
