param(
    [string]$JavaHome
)

. (Join-Path $PSScriptRoot 'common.ps1')

$repoRoot = Get-RepoRoot
Set-LocalAppEnvironment -JavaHome $JavaHome

if ([string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
    throw '未检测到 OPENAI_API_KEY，请先在系统环境变量或当前终端中配置。'
}
Push-Location $repoRoot
try {
    Write-Host '已写入本地启动环境变量：MYSQL_URL / PGVECTOR_URL / AI_AGENT_ES_BASE_URL / AI_AGENT_VECTOR_STORE_ENABLED'
    Write-Host '开始构建应用包...'
    & mvn -q -pl ai-agent-station-app -am -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw '应用构建失败。'
    }

    $jarFile = Get-ChildItem (Join-Path $repoRoot 'ai-agent-station-app\target') -Filter 'ai-agent-station-app*.jar' |
        Where-Object { -not $_.Name.StartsWith('original-', [System.StringComparison]::OrdinalIgnoreCase) } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $jarFile) {
        throw '未找到可启动的应用 jar 包。'
    }

    Write-Host "开始启动 Spring Boot，jar: $($jarFile.FullName)"
    Write-Host '本命令会占用当前终端，停止请按 Ctrl+C。'
    & java '-jar' $jarFile.FullName '--spring.profiles.active=dev'
    if ($LASTEXITCODE -ne 0) {
        throw 'Spring Boot 启动失败。'
    }
} finally {
    Pop-Location
}
