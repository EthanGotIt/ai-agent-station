param(
    [switch]$Pull,
    [switch]$WithTools,
    [switch]$WithExtras
)

. (Join-Path $PSScriptRoot 'common.ps1')

$repoRoot = Get-RepoRoot
$composePath = Get-ComposePath

Assert-DockerReady

$composeArgs = @('compose', '-f', $composePath)
if ($WithTools) {
    $composeArgs += @('--profile', 'tools')
}
if ($WithExtras) {
    $composeArgs += @('--profile', 'extras')
}

Push-Location $repoRoot
try {
    if ($Pull) {
        & docker @composeArgs pull
        if ($LASTEXITCODE -ne 0) {
            throw '拉取本地依赖镜像失败。'
        }
    }

    & docker @composeArgs up -d
    if ($LASTEXITCODE -ne 0) {
        throw '拉起本地依赖失败。'
    }

    Wait-ContainerHealthy -ContainerName 'ai-agent-mysql-local'
    Wait-ContainerHealthy -ContainerName 'ai-agent-pgvector-local'
    Wait-ContainerHealthy -ContainerName 'ai-agent-elasticsearch-local'

    Write-Host '本地核心依赖已就绪。默认仅启动 MySQL / PGVector / Elasticsearch。'
    if (-not $WithTools -and -not $WithExtras) {
        Write-Host '如需 CloudBeaver / Kibana / RedisInsight，请追加 -WithTools 或 -WithExtras。'
    }
} finally {
    Pop-Location
}
