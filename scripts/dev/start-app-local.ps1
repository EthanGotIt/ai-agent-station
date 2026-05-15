param(
    [string]$JavaHome = $env:JAVA_HOME
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
    Write-Host '开始启动 Spring Boot，本命令会占用当前终端，停止请按 Ctrl+C。'
    & mvn -pl ai-agent-station-app spring-boot:run '-Dspring-boot.run.profiles=dev'
    if ($LASTEXITCODE -ne 0) {
        throw 'Spring Boot 启动失败。'
    }
} finally {
    Pop-Location
}
